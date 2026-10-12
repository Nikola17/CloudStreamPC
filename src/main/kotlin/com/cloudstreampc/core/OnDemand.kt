package com.cloudstreampc.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Programs downloaded the first time they are needed instead of being in the installer, which
 * keeps the download of the app small: the torrent engine (60 Mo) on the first torrent, yt-dlp
 * on the first trailer. Every file is checked against its SHA-256 before being used.
 */
object OnDemand {
    enum class Tool(val label: String) { TORRSERVER("moteur torrent"), YTDLP("lecteur de bandes-annonces") }

    private data class Asset(val url: String, val sha256: String?, val sumsUrl: String?, val fileName: String)

    private val os = System.getProperty("os.name").lowercase()
    private val arm = System.getProperty("os.arch").lowercase().let { "aarch64" in it || "arm64" in it }
    private val isWindows = "win" in os
    private val isMac = "mac" in os

    private const val TORRSERVER_VERSION = "MatriX.146.1"
    /** Pinned TorrServer builds and their SHA-256. */
    private val torrServer = mapOf(
        "TorrServer-windows-amd64.exe" to "ac2bbda1d89230739306ae733fd29777b2fceb7f560702fca96b757ab267c2ff",
        "TorrServer-linux-amd64" to "8f6a9f56fcb290055e7737b99c873f1971f26c157b080e1d9980caf8ad095226",
        "TorrServer-linux-arm64" to "72a5b48a8e08203805438a0bb8769887940f673127237cf0926bf5aa15a4ab8b",
        "TorrServer-darwin-arm64" to "a9995fe6f9a2766d8db1fe3ab2e201108fb787c27563831307d4d2e9d29b6fb7",
        "TorrServer-darwin-amd64" to "6010b5bd8b7541b014522b8dbf1d8a3429de3133daed67dc2a5c84736efd4065",
    )

    /** yt-dlp follows YouTube's changes: always its latest release, checked against the release's SHA2-256SUMS. */
    internal var ytDlpBase = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
    internal var torrServerBase = "https://github.com/YouROK/TorrServer/releases/download/$TORRSERVER_VERSION"
    /** yt-dlp older than this is replaced by the latest one in the background. */
    private const val YTDLP_MAX_AGE_MS = 30L * 24 * 3600_000

    val dir: File get() = File(AppDirs.root, "tools").apply { mkdirs() }

    /** "Téléchargement du moteur torrent · 42 %" while a tool downloads, for the app's status banner. */
    var progress by mutableStateOf<String?>(null)
        private set

    private val mutex = Mutex()

    private fun asset(tool: Tool): Asset? = when (tool) {
        Tool.TORRSERVER -> {
            val name = when {
                isWindows -> "TorrServer-windows-amd64.exe"
                isMac -> if (arm) "TorrServer-darwin-arm64" else "TorrServer-darwin-amd64"
                else -> if (arm) "TorrServer-linux-arm64" else "TorrServer-linux-amd64"
            }
            Asset("$torrServerBase/$name", torrServer[name], null, if (isWindows) "TorrServer.exe" else "TorrServer")
        }
        Tool.YTDLP -> {
            val name = when {
                isWindows -> "yt-dlp.exe"
                isMac -> "yt-dlp_macos"
                else -> if (arm) "yt-dlp_linux_aarch64" else "yt-dlp_linux"
            }
            Asset("$ytDlpBase/$name", null, "$ytDlpBase/SHA2-256SUMS", if (isWindows) "yt-dlp.exe" else "yt-dlp")
        }
    }

    /** The tool already downloaded, if any. */
    fun installed(tool: Tool): File? = asset(tool)?.let { File(dir, it.fileName) }?.takeIf { it.isFile && it.length() > 0 }

    /** yt-dlp is old: YouTube may have changed since. */
    fun outdated(tool: Tool): Boolean = tool == Tool.YTDLP &&
        installed(tool)?.let { System.currentTimeMillis() - it.lastModified() > YTDLP_MAX_AGE_MS } == true

    /** The tool, downloaded (and checked) first if needed. */
    suspend fun ensure(tool: Tool, refresh: Boolean = false): File = mutex.withLock {
        if (!refresh) installed(tool)?.let { return it }
        val asset = asset(tool) ?: error("Pas de ${tool.label} pour ce système")
        withContext(Dispatchers.IO) {
            try {
                download(tool, asset)
            } finally {
                progress = null
            }
        }
    }

    private fun download(tool: Tool, asset: Asset): File {
        val expected = asset.sha256 ?: asset.sumsUrl?.let { sums ->
            val name = asset.url.substringAfterLast('/')
            fetch(sums).use { it.body.string() }.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
                .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }?.get(0)
        } ?: error("Empreinte de ${tool.label} introuvable")
        val target = File(dir, asset.fileName)
        val part = File(dir, asset.fileName + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        Logger.info("Téléchargement du ${tool.label} : ${asset.url}")
        fetch(asset.url).use { response ->
            val total = response.body.contentLength()
            response.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            progress = "Téléchargement du ${tool.label}" + if (percent >= 0) " · $percent %" else "…"
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            part.delete()
            error("Le ${tool.label} téléchargé est corrompu (empreinte SHA-256 différente)")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        if (!isWindows) target.setExecutable(true)
        Logger.info("${tool.label} installé (${target.length() / 1_000_000} Mo)")
        return target
    }

    private fun fetch(url: String) = app.baseClient.newCall(Request.Builder().url(url).header("User-Agent", "CloudStreamPC").build())
        .execute().also { if (!it.isSuccessful) { it.close(); error("HTTP ${it.code}") } }
}
