package com.cloudstreampc.player

import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLEncoder

/**
 * Plays torrents (magnet links of extensions and Stremio addons such as Torrentio) with
 * TorrServer, shipped with the app: it downloads the pieces in order and serves the file over
 * HTTP on this computer, so mpv plays it like any other video.
 */
object TorrentEngine {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val mutex = Mutex()
    @Volatile private var process: Process? = null

    internal var port = 8097
    internal var startTimeoutMs = 20_000L
    /** Tests use a fake server already running. */
    internal var managed = true

    val base get() = "http://127.0.0.1:$port"

    private val videoExtensions = setOf("mkv", "mp4", "avi", "m4v", "webm", "mov", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg")

    fun isTorrent(link: ExtractorLink) =
        link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT || link.url.startsWith("magnet:")

    /** TorrServer shipped with the app, or installed on the system. */
    fun executable(): File? {
        val names = if (isWindows) listOf("TorrServer.exe", "TorrServer-windows-amd64.exe") else listOf("TorrServer", "TorrServer-linux-amd64", "TorrServer-darwin-arm64", "TorrServer-darwin-amd64", "torrserver")
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            names.map { File(dir, "torrserver/$it") }.firstOrNull { it.isFile }?.let { return it }
        }
        com.cloudstreampc.core.OnDemand.installed(com.cloudstreampc.core.OnDemand.Tool.TORRSERVER)?.let { return it }
        return System.getenv("PATH")?.split(File.pathSeparator)?.flatMap { dir -> names.map { File(dir, it) } }?.firstOrNull { it.isFile }
    }

    /** Torrents can be played (the engine is downloaded on the first one when needed). */
    val available get() = Store.settings.torrentsEnabled

    private suspend fun alive(): Boolean = runCatching { app.get("$base/echo", timeout = 2).isSuccessful }.getOrDefault(false)

    /** Starts TorrServer (bound to this computer only) unless it is already running. */
    suspend fun ensureStarted() = mutex.withLock {
        if (alive()) return@withLock
        check(managed) { "Moteur torrent injoignable." }
        val exe = (executable() ?: com.cloudstreampc.core.OnDemand.ensure(com.cloudstreampc.core.OnDemand.Tool.TORRSERVER))
            .let(com.cloudstreampc.core.Bundled::runnable)
        val dir = File(AppDirs.root, "torrserver").apply { mkdirs() }
        Logger.info("Démarrage du moteur torrent sur le port $port")
        process = ProcessBuilder(exe.path, "-p", "$port", "-d", dir.path, "--ip", "127.0.0.1")
            .redirectErrorStream(true).redirectOutput(File(AppDirs.logs, "torrserver.log")).start()
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() })
        withTimeout(startTimeoutMs) { while (!alive()) delay(300) }
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** Our magnets carry the Stremio file index as "&cspc_index=N" (0-based): split it out. */
    fun splitIndex(magnet: String): Pair<String, Int?> {
        val match = Regex("[&?]cspc_index=(\\d+)").find(magnet) ?: return magnet to null
        return magnet.removeRange(match.range) to match.groupValues[1].toInt()
    }

    /** Files of the torrent once its metadata is known (TorrServer asks the peers for it). */
    private suspend fun files(link: String): List<JsonNode> = withTimeout(90_000) {
        var files = emptyList<JsonNode>()
        while (files.isEmpty()) {
            files = runCatching {
                mapper.readTree(app.get("$base/stream/stat?link=${enc(link)}&stat", timeout = 60).text)
            }.getOrNull()?.path("file_stats")?.toList().orEmpty()
            if (files.isEmpty()) delay(1_000)
        }
        files
    }

    /** File to play: the requested episode, else the biggest video. */
    internal fun pickFile(files: List<JsonNode>, season: Int?, episode: Int?): JsonNode? {
        val videos = files.filter { it.path("path").asText().substringAfterLast('.').lowercase() in videoExtensions }.ifEmpty { files }
        if (episode != null) {
            val s = season ?: 1
            val patterns = listOf(
                Regex("(?i)s0*${s}[ ._-]*e0*${episode}(?!\\d)"),
                Regex("(?i)(?<!\\d)${s}x0*${episode}(?!\\d)"),
                Regex("(?i)(?:ep(?:isode)?|e)[ ._-]*0*${episode}(?!\\d)"),
            )
            patterns.firstNotNullOfOrNull { p -> videos.firstOrNull { p.containsMatchIn(it.path("path").asText()) } }?.let { return it }
        }
        return videos.maxByOrNull { it.path("length").asLong() }
    }

    /**
     * Turns a magnet / torrent link into an HTTP link mpv can play.
     * @param playable used to find the episode inside a whole-season torrent
     */
    suspend fun prepare(link: ExtractorLink, playable: Playable?): String = withContext(Dispatchers.IO) {
        ensureStarted()
        val (torrent, stremioIndex) = splitIndex(link.url)
        val index = if (stremioIndex != null) {
            stremioIndex + 1 // TorrServer counts files from 1
        } else {
            val file = pickFile(files(torrent), playable?.season, playable?.episode) ?: error("Aucune vidéo dans ce torrent.")
            file.path("id").asInt(1)
        }
        val name = (playable?.title ?: "video").replace(Regex("[^\\w .-]"), "").ifBlank { "video" }
        "$base/stream/${enc(name)}?link=${enc(torrent)}&index=$index&play"
    }
}
