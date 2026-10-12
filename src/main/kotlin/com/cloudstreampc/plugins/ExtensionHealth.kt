package com.cloudstreampc.plugins

import androidx.compose.runtime.mutableStateMapOf
import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.readJsonFile
import com.cloudstreampc.core.writeJsonFile
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File

data class HealthResult(val ok: Boolean = true, val checkedAt: Long = 0, val detail: String? = null)

private data class HealthFile(val lastRun: Long = 0, val results: Map<String, HealthResult> = emptyMap())

/**
 * Checks every week that each site still answers (a search, or its home page), so a broken one
 * shows an "En panne" badge instead of failing silently. Nothing is checked without Internet.
 */
object ExtensionHealth {
    private const val WEEK = 7L * 24 * 3600 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file get() = File(AppDirs.root, "extension-health.json")

    /** By provider name. */
    val results = mutableStateMapOf<String, HealthResult>()
    @Volatile var lastRun = 0L
        private set
    @Volatile var running = false
        private set

    init {
        val saved = readJsonFile(file) { HealthFile() }
        lastRun = saved.lastRun
        results.putAll(saved.results)
    }

    fun broken(name: String) = results[name]?.ok == false

    /** At startup: once a week. */
    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (now - lastRun >= WEEK) scope.launch { runAll() }
    }

    /** Tests every provider (4 at a time), unless the computer is offline. */
    suspend fun runAll(providers: List<MainAPI> = PluginManager.providers.filter { it.sourcePlugin != "builtin" }): Map<String, HealthResult> {
        if (running) return results.toMap()
        running = true
        try {
            val online = runCatching { app.get("https://www.gstatic.com/generate_204", timeout = 10).code in 200..399 }.getOrDefault(false)
            if (!online) {
                Logger.info("Santé des extensions : pas de connexion, test reporté")
                return results.toMap()
            }
            val permits = Semaphore(4)
            val checked = kotlinx.coroutines.coroutineScope {
                providers.map { api -> async { permits.withPermit { api.name to check(api) } } }.awaitAll()
            }
            checked.forEach { (name, result) -> results[name] = result }
            lastRun = System.currentTimeMillis()
            runCatching { writeJsonFile(file, HealthFile(lastRun, results.toMap())) }
            Logger.info("Santé des extensions : ${checked.count { !it.second.ok }} en panne sur ${checked.size}")
            return results.toMap()
        } finally {
            running = false
        }
    }

    /** A site works when a search or its first home section returns something. */
    suspend fun check(api: MainAPI): HealthResult {
        val now = System.currentTimeMillis()
        val search = runCatching { withTimeout(30_000) { api.search("the", 1)?.items?.size ?: 0 } }
        if ((search.getOrNull() ?: 0) > 0) return HealthResult(true, now)
        val home = api.mainPage.firstOrNull()?.takeIf { api.hasMainPage }?.let { section ->
            runCatching { withTimeout(30_000) { api.getMainPage(1, MainPageRequest(section.name, section.data, section.horizontalImages))?.items?.sumOf { it.list.size } ?: 0 } }
        }
        if ((home?.getOrNull() ?: 0) > 0) return HealthResult(true, now)
        val error = search.exceptionOrNull() ?: home?.exceptionOrNull()
        return HealthResult(false, now, error?.let { it.message ?: it.javaClass.simpleName } ?: "Aucun résultat")
    }
}
