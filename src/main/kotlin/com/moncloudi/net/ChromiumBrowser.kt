package com.moncloudi.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moncloudi.core.AppDirs
import com.moncloudi.core.Logger
import com.moncloudi.core.Store
import com.moncloudi.core.compactJson
import com.moncloudi.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The web browser some extensions need (Android's WebView): pages that only reveal their video
 * address once their JavaScript ran, and Cloudflare's "checking your browser".
 *
 * Instead of shipping a browser (100 Mo), the app drives the Chromium browser already on the
 * computer (Edge on every Windows, Chrome, Brave, Chromium) through its DevTools protocol, in the
 * background and with its own profile. When a site insists on a human check, the same browser
 * opens a small visible window for it.
 */
object ChromiumBrowser {
    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = "win" in os
    private val isMac = "mac" in os
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** "Vérification de site.com : terminez-la dans la fenêtre ouverte" while the user is asked. */
    var status by mutableStateOf<String?>(null)
        private set

    /** User-Agent of the browser without "Headless": the same in the background and in the window. */
    @Volatile var userAgent: String? = null
        private set

    private var instance: Instance? = null
    @Volatile private var lastUse = 0L

    /** Browsers to drive, best first. */
    fun candidates(): List<File> {
        val list = mutableListOf<File>()
        Store.settings.browserPath.takeIf { it.isNotBlank() }?.let { list += File(it) }
        System.getenv("MONCLOUDI_BROWSER")?.let { list += File(it) }
        System.getProperty("moncloudi.browser")?.let { list += File(it) }
        when {
            isWindows -> {
                val roots = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
                roots.forEach { root ->
                    list += File(root, "Microsoft/Edge/Application/msedge.exe")
                    list += File(root, "Google/Chrome/Application/chrome.exe")
                    list += File(root, "BraveSoftware/Brave-Browser/Application/brave.exe")
                    list += File(root, "Chromium/Application/chrome.exe")
                }
            }
            isMac -> listOf("Google Chrome", "Microsoft Edge", "Brave Browser", "Chromium").forEach {
                list += File("/Applications/$it.app/Contents/MacOS/$it")
            }
            else -> {
                val names = listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "microsoft-edge-stable", "brave-browser")
                System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir -> names.forEach { list += File(dir, it) } }
                list += File("/snap/bin/chromium")
            }
        }
        return list.distinct()
    }

    fun find(): File? = candidates().firstOrNull { it.isFile && it.canExecute() }

    val available: Boolean get() = find() != null

    // ----- DevTools connection -----

    private class Instance(
        val process: Process,
        val socket: WebSocket,
        val headless: Boolean,
        val exeName: String,
        val profileDir: File,
        val isolated: Boolean,
    ) {
        val nextId = AtomicInteger()
        val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonNode>>()
        val listeners = CopyOnWriteArrayList<(String?, String, JsonNode) -> Unit>()
        @Volatile var closed = false

        suspend fun call(method: String, params: Map<String, Any?> = emptyMap(), session: String? = null, timeoutMs: Long = 15_000): JsonNode {
            val id = nextId.incrementAndGet()
            val answer = CompletableDeferred<JsonNode>()
            pending[id] = answer
            val message = mutableMapOf<String, Any?>("id" to id, "method" to method, "params" to params.filterValues { it != null })
            if (session != null) message["sessionId"] = session
            if (!socket.send(compactJson(message))) {
                pending.remove(id)
                error("Navigateur déconnecté")
            }
            return try {
                withTimeout(timeoutMs) { answer.await() }
            } finally {
                pending.remove(id)
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close(1000, null) }
            // On Windows Chromium spawns renderer/GPU/crashpad children that can keep profile lockfiles open
            // if only the parent PID is terminated.
            val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
            runCatching { process.destroy() }
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                runCatching { process.destroyForcibly() }
            }
            descendants.forEach { child -> runCatching { if (child.isAlive) child.destroyForcibly() } }
            if (isolated) runCatching { profileDir.deleteRecursively() }
        }
    }

    /**
     * Per-browser profile directory (Edge and Chrome have incompatible Crashpad/Local State formats and
     * must never share the same --user-data-dir). If the shared profile is locked by another process,
     * we fall back to a fresh isolated per-run directory so retries never collide on a stale lockfile.
     */
    private val isolatedSeq = AtomicInteger()
    private val createdIsolatedDirs = CopyOnWriteArrayList<File>()

    private fun browserTag(exe: File): String =
        exe.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "chromium" }

    private fun sharedProfileFor(exe: File): File =
        File(AppDirs.cache, "browser-${browserTag(exe)}").apply { mkdirs() }

    private fun isolatedProfileFor(exe: File): File =
        File(AppDirs.cache, "browser-${browserTag(exe)}-${ProcessHandle.current().pid()}-${isolatedSeq.incrementAndGet()}")
            .apply { mkdirs(); createdIsolatedDirs += this }

    /** Removes leftover per-PID browser profiles from previous runs that are no longer alive. */
    private fun cleanupStaleProfiles() {
        runCatching {
            val myPid = ProcessHandle.current().pid()
            val pidRegex = Regex("""^browser-(?:[a-z0-9]+-)?(\d+)(?:-\d+)?$""")
            AppDirs.cache.listFiles { f -> f.isDirectory && f.name.startsWith("browser-") }?.forEach { dir ->
                val pid = pidRegex.matchEntire(dir.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
                if (pid != myPid && ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) {
                    runCatching { dir.deleteRecursively() }
                }
            }
        }
    }

    private fun launch(headless: Boolean, startUrl: String = "about:blank"): Instance {
        cleanupStaleProfiles()
        val available = candidates().filter { it.isFile && it.canExecute() }
        if (available.isEmpty()) error("Aucun navigateur Chromium (Edge, Chrome, Brave, Chromium) n'est installé")
        var lastError: Throwable? = null
        // Try each browser with its own shared profile first (keeps solved cookies), then fall back to
        // a brand-new isolated profile if locked/occupied on Windows.
        for (exe in available) {
            val sharedDir = sharedProfileFor(exe)
            try {
                return launchWith(exe, sharedDir, isolated = false, headless = headless, startUrl = startUrl)
            } catch (e: Throwable) {
                lastError = e
                Logger.info("Profil navigateur occupé ou erreur au démarrage (${exe.name}) : bascule sur un profil isolé")
                val isolatedDir = isolatedProfileFor(exe)
                try {
                    return launchWith(exe, isolatedDir, isolated = true, headless = headless, startUrl = startUrl)
                } catch (e2: Throwable) {
                    lastError = e2
                    runCatching { isolatedDir.deleteRecursively() }
                }
                Logger.info("Échec du lancement de ${exe.name} : ${lastError.message}")
            }
        }
        throw lastError ?: IllegalStateException("Impossible de démarrer le navigateur Chromium")
    }

    private fun launchWith(exe: File, profileDir: File, isolated: Boolean, headless: Boolean, startUrl: String): Instance {
        val portFile = File(profileDir, "DevToolsActivePort").apply { delete() }
        val args = mutableListOf(
            exe.path,
            "--remote-debugging-port=0",
            "--user-data-dir=${profileDir.path}",
            "--no-first-run", "--no-default-browser-check", "--disable-sync", "--disable-extensions",
            "--disable-background-networking", "--disable-component-update", "--mute-audio",
            "--disable-background-timer-throttling", "--disable-renderer-backgrounding",
            "--disable-backgrounding-occluded-windows",
            // Players live in frames from other sites: kept in the page's process, their requests (the video
            // address a WebViewResolver waits for) are reported with the page's. Videos start without a click.
            "--disable-features=Translate,MediaRouter,OptimizationHints,IsolateOrigins,site-per-process",
            "--disable-site-isolation-trials",
            "--autoplay-policy=no-user-gesture-required",
            "--disable-blink-features=AutomationControlled",
            "--window-size=1100,800",
        )
        if (headless) args += "--headless=new"
        userAgent?.let { args += "--user-agent=$it" }
        if (!isWindows && System.getProperty("user.name") == "root") args += "--no-sandbox"
        if (headless) args += startUrl else args += "--app=$startUrl"
        val logFile = File(AppDirs.logs, if (isolated) "browser-${browserTag(exe)}-${ProcessHandle.current().pid()}.log" else "browser-${browserTag(exe)}.log")
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(logFile).start()
        val deadline = System.currentTimeMillis() + 20_000
        while (!portFile.isFile || portFile.readLines().size < 2) {
            if (!process.isAlive) error("Le navigateur s'est fermé au démarrage (voir ${logFile.name})")
            if (System.currentTimeMillis() > deadline) {
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                error("Le navigateur ne répond pas")
            }
            Thread.sleep(100)
        }
        val (port, path) = portFile.readLines()
        val holder = arrayOfNulls<Instance>(1)
        val socket = app.baseClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            .newWebSocket(Request.Builder().url("ws://127.0.0.1:${port.trim()}${path.trim()}").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val instance = holder[0] ?: return
                    val node = runCatching { mapper.readTree(text) }.getOrNull() ?: return
                    val id = node.get("id")?.asInt()
                    if (id != null) {
                        instance.pending[id]?.let { answer ->
                            if (node.has("error")) answer.completeExceptionally(IllegalStateException(node.get("error").path("message").asText()))
                            else answer.complete(node.path("result"))
                        }
                        return
                    }
                    val method = node.path("method").asText()
                    val session = node.get("sessionId")?.asText()
                    instance.listeners.forEach { runCatching { it(session, method, node.path("params")) } }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    holder[0]?.let { it.closed = true; it.pending.values.forEach { p -> p.completeExceptionally(t) } }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    holder[0]?.closed = true
                }
            })
        return Instance(process, socket, headless, exe.name, profileDir, isolated).also { holder[0] = it }
    }

    /** The background browser, started (or restarted) when needed. */
    private suspend fun background(): Instance = mutex.withLock {
        instance?.takeIf { !it.closed && it.process.isAlive && it.headless }?.let { return it }
        instance?.close()
        instance = null
        val started = launch(headless = true)
        instance = started
        if (userAgent == null) {
            runCatching { started.call("Browser.getVersion").path("userAgent").asText() }.getOrNull()
                ?.replace("HeadlessChrome", "Chrome")?.takeIf { it.isNotBlank() }?.let {
                    userAgent = it
                    com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent = it
                }
        }
        Logger.info("Navigateur lancé en arrière-plan : ${started.exeName}")
        scheduleIdleStop()
        started
    }

    private fun scheduleIdleStop() {
        scope.launch {
            while (true) {
                delay(30_000)
                val current = instance ?: return@launch
                if (System.currentTimeMillis() - lastUse > 180_000 && pages.get() == 0) {
                    mutex.withLock { if (instance === current) { current.close(); instance = null } }
                    Logger.info("Navigateur arrêté (inutilisé)")
                    return@launch
                }
            }
        }
    }

    private val pages = AtomicInteger()

    /** A request seen by a page: address, method, headers. */
    data class SeenRequest(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

    /**
     * Opens [url] in a hidden tab and reports every request the page makes until [onRequest]
     * returns true or [timeoutMs] passes. [script] runs once the page is loaded.
     * [onRequest] runs on the connection's thread: it must not wait for the browser.
     */
    suspend fun browse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        userAgentOverride: String? = null,
        script: String? = null,
        onScript: ((String) -> Unit)? = null,
        timeoutMs: Long = 60_000,
        onRequest: (SeenRequest) -> Boolean,
    ) {
        val browser = background()
        val effectiveUserAgent = userAgentOverride?.takeIf { it.isNotBlank() } ?: userAgent
        lastUse = System.currentTimeMillis()
        pages.incrementAndGet()
        val target = browser.call("Target.createTarget", mapOf("url" to "about:blank")).path("targetId").asText()
        val session = browser.call("Target.attachToTarget", mapOf("targetId" to target, "flatten" to true)).path("sessionId").asText()
        val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add(session) }
        val done = CompletableDeferred<Unit>()
        val listener: (String?, String, JsonNode) -> Unit = { s, method, params ->
            if (s in sessions) when (method) {
                "Target.attachedToTarget" -> {
                    val childSession = params.path("sessionId").asText()
                    if (childSession.isNotBlank() && sessions.add(childSession)) {
                        scope.launch {
                            runCatching { browser.call("Network.enable", session = childSession) }
                            effectiveUserAgent?.let { runCatching { browser.call("Network.setUserAgentOverride", mapOf("userAgent" to it), childSession) } }
                            runCatching {
                                browser.call(
                                    "Target.setAutoAttach",
                                    mapOf("autoAttach" to true, "waitForDebuggerOnStart" to false, "flatten" to true),
                                    childSession,
                                )
                            }
                        }
                    }
                }
                "Network.requestWillBeSent" -> {
                    val request = params.path("request")
                    val seen = SeenRequest(
                        request.path("url").asText(),
                        request.path("method").asText("GET"),
                        request.path("headers").fields().asSequence().associate { it.key to it.value.asText() },
                        request.get("postData")?.asText(),
                    )
                    if (!seen.url.startsWith("data:") && runCatching { onRequest(seen) }.getOrDefault(false)) done.complete(Unit)
                }
                "Page.loadEventFired" -> if (s == session && script != null) scope.launch {
                    runCatching {
                        val result = browser.call(
                            "Runtime.evaluate",
                            mapOf("expression" to script, "awaitPromise" to true, "returnByValue" to true),
                            session,
                        ).path("result").path("value")
                        onScript?.invoke(mapper.writeValueAsString(result))
                    }
                }
            }
        }
        browser.listeners += listener
        try {
            browser.call("Network.enable", session = session)
            browser.call("Page.enable", session = session)
            // Attach to any cross-origin iframe targets (OOPiFs) so embedded sports/video players' stream requests are always seen.
            runCatching {
                browser.call(
                    "Target.setAutoAttach",
                    mapOf("autoAttach" to true, "waitForDebuggerOnStart" to false, "flatten" to true),
                    session,
                )
            }
            // Several pages at once (one per player of an event): each behaves as the visible, focused tab,
            // otherwise the players of the others don't start and never ask for their stream.
            runCatching { browser.call("Emulation.setFocusEmulationEnabled", mapOf("enabled" to true), session) }
            // Honor the extension's requested User-Agent (so hosts that bind signed HLS tokens to the UA,
            // e.g. nginx secure_link on live sports players, match what the extension & player send next),
            // and never expose "HeadlessChrome".
            effectiveUserAgent?.let { runCatching { browser.call("Network.setUserAgentOverride", mapOf("userAgent" to it), session) } }
            val extra = headers.filterKeys { !it.equals("user-agent", true) && !it.equals("referer", true) }
            if (extra.isNotEmpty()) browser.call("Network.setExtraHTTPHeaders", mapOf("headers" to extra), session)
            browser.call("Page.navigate", mapOf("url" to url, "referrer" to referer), session)
            runCatching { withTimeout(timeoutMs) { done.await() } }
        } finally {
            browser.listeners -= listener
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { browser.call("Target.closeTarget", mapOf("targetId" to target)) }
            }
            pages.decrementAndGet()
            lastUse = System.currentTimeMillis()
        }
    }

    /** Cookies the browser holds for [url], as name → value. */
    suspend fun cookies(url: String): Map<String, String> {
        val browser = background()
        val list = browser.call("Storage.getCookies").path("cookies")
        val host = java.net.URI(url).host ?: return emptyMap()
        return list.filter { cookie ->
            val domain = cookie.path("domain").asText().removePrefix(".")
            host == domain || host.endsWith(".$domain")
        }.associate { it.path("name").asText() to it.path("value").asText() }
    }

    /**
     * The site wants a person (captcha): shows it in a small browser window and waits until
     * [solved] says the cookies are there, the window is closed, or 3 minutes pass.
     */
    suspend fun askUser(url: String, solved: (Map<String, String>) -> Boolean): Map<String, String>? = mutex.withLock {
        instance?.close()
        instance = null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        status = "Vérification de $host : terminez-la dans la fenêtre ouverte"
        val window = runCatching { launch(headless = false, startUrl = url) }.getOrElse {
            status = null
            Logger.error("Fenêtre de vérification", it)
            return@withLock null
        }
        try {
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline && window.process.isAlive && !window.closed) {
                delay(1_000)
                val list = runCatching { window.call("Storage.getCookies").path("cookies") }.getOrNull() ?: continue
                val cookies = list.filter { cookie ->
                    val domain = cookie.path("domain").asText().removePrefix(".")
                    host == domain || host.endsWith(".$domain")
                }.associate { it.path("name").asText() to it.path("value").asText() }
                if (solved(cookies)) return@withLock cookies
            }
            null
        } finally {
            window.close()
            status = null
        }
    }

    fun shutdown() {
        instance?.close()
        instance = null
        createdIsolatedDirs.forEach { runCatching { it.deleteRecursively() } }
        createdIsolatedDirs.clear()
    }
}

/** Opens [url] in the background until [done] (checked every half second) or [timeoutMs]. */
suspend fun ChromiumBrowser.browseUntil(url: String, timeoutMs: Long, done: suspend () -> Boolean): Boolean = kotlinx.coroutines.coroutineScope {
    val page = launch { runCatching { browse(url, timeoutMs = timeoutMs) { false } } }
    val deadline = System.currentTimeMillis() + timeoutMs
    var ok = false
    while (System.currentTimeMillis() < deadline && page.isActive) {
        delay(500)
        if (runCatching { done() }.getOrDefault(false)) { ok = true; break }
    }
    if (!ok) ok = runCatching { done() }.getOrDefault(false)
    page.cancel()
    ok
}
