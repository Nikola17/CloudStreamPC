package com.cloudstreampc.providers

import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.mapper
import com.cloudstreampc.core.readJsonFile
import com.cloudstreampc.core.writeJsonFile
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Trailers: the YouTube video given by the extension, or the one Cinemeta (Stremio's catalog)
 * lists for the same title; yt-dlp turns it into a stream mpv can play.
 */
object Trailers {
    internal var cinemetaBase = "https://v3-cinemeta.strem.io"
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private val cacheFile: File get() = File(AppDirs.cache, "trailers.json")
    /** "title|year" -> YouTube id, or "" when there is none. */
    private val ids: MutableMap<String, String> by lazy { readJsonFile<MutableMap<String, String>>(cacheFile) { mutableMapOf() } }
    private val streams = mutableMapOf<String, Pair<String, Long>>()

    private val youtubePattern = Regex("""(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|v/)|youtu\.be/)([\w-]{11})""")

    fun youtubeIdOf(url: String?): String? = url?.let { youtubePattern.find(it)?.groupValues?.get(1) }

    fun youtubeUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    internal fun clearCache() {
        synchronized(ids) { ids.clear() }
        cacheFile.delete()
    }

    /** YouTube id of the trailer, or null when none is known. */
    suspend fun youtubeId(response: LoadResponse): String? {
        response.trailers.firstNotNullOfOrNull { youtubeIdOf(it.extractorUrl) }?.let { return it }
        val key = Recommender.normalizedTitle(response.name) + "|" + (response.year ?: "")
        synchronized(ids) { ids[key] }?.let { return it.ifEmpty { null } }
        val found = try {
            withContext(Dispatchers.IO) { searchCinemeta(response) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.info("Bande-annonce introuvable pour ${response.name} : ${t.message}")
            return null
        }
        synchronized(ids) {
            ids[key] = found.orEmpty()
            runCatching { writeJsonFile(cacheFile, ids) }
        }
        return found
    }

    private suspend fun searchCinemeta(response: LoadResponse): String? {
        val type = if (Recommender.family(response.type.name) == "movie") "movie" else "series"
        val name = response.name.replace(Regex("""\s*[\[(](VF|VOSTFR|FRENCH|MULTI)[])]""", RegexOption.IGNORE_CASE), "").trim()
        val search = mapper.readTree(app.get("$cinemetaBase/catalog/$type/top/search=${URLEncoder.encode(name, Charsets.UTF_8)}.json", timeout = 15).text)
        val wanted = Recommender.normalizedTitle(name)
        val year = response.year
        val metas = search.path("metas").toList()
        fun yearOf(node: com.fasterxml.jackson.databind.JsonNode) = node.path("releaseInfo").asText().take(4).toIntOrNull()
        val sameTitle = metas.filter { Recommender.normalizedTitle(it.path("name").asText()) == wanted }
        val meta = sameTitle.firstOrNull { year == null || yearOf(it) == null || yearOf(it) == year }
            ?: metas.singleOrNull { year != null && yearOf(it) == year }
            ?: return null
        val id = meta.path("id").asText().takeIf { it.isNotBlank() } ?: return null
        val details = mapper.readTree(app.get("$cinemetaBase/meta/$type/$id.json", timeout = 15).text).path("meta")
        return details.path("trailerStreams").firstNotNullOfOrNull { it.path("ytId").asText().takeIf { s -> s.length == 11 } }
            ?: details.path("trailers").firstNotNullOfOrNull { it.path("source").asText().takeIf { s -> s.length == 11 } }
    }

    /** yt-dlp shipped with the app (Windows), or the one installed on the system. */
    fun ytDlp(): String? {
        val exe = if (isWindows) "yt-dlp.exe" else "yt-dlp"
        System.getProperty("compose.application.resources.dir")?.let { File(it, "yt-dlp/$exe") }?.takeIf { it.isFile }?.let { return it.path }
        return System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, exe) }?.firstOrNull { it.isFile }?.path
    }

    /** Direct video stream (no sound, 720p at most) of a YouTube trailer, for the home banner. */
    suspend fun silentStream(youtubeId: String): String? = withContext(Dispatchers.IO) {
        synchronized(streams) { streams[youtubeId] }?.takeIf { System.currentTimeMillis() - it.second < 3600_000L }?.let { return@withContext it.first }
        val ytDlp = ytDlp() ?: return@withContext null
        val process = ProcessBuilder(
            ytDlp, "-g", "--no-warnings", "--no-playlist",
            "-f", "bv*[height<=720][vcodec^=avc1]/bv*[height<=720]/b[height<=720]/b",
            youtubeUrl(youtubeId),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        // The output is a couple of URLs: it fits in the pipe buffer, so wait first (with a timeout).
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext null
        }
        val output = process.inputStream.bufferedReader().readText()
        val url = output.lineSequence().firstOrNull { it.startsWith("http") }?.trim()
        if (url == null) Logger.info("yt-dlp n'a pas trouvé la bande-annonce $youtubeId")
        url?.also { synchronized(streams) { streams[youtubeId] = it to System.currentTimeMillis() } }
    }
}
