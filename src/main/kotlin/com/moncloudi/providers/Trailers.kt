package com.moncloudi.providers

import com.moncloudi.core.OnDemand

import com.moncloudi.core.AppDirs
import com.moncloudi.core.Logger
import com.moncloudi.core.Recommender
import com.moncloudi.core.mapper
import com.moncloudi.core.readJsonFile
import com.moncloudi.core.writeJsonFile
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
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private val cacheFile: File get() = File(AppDirs.cache, "trailers.json")
    /** "title|year" -> YouTube id, or "" when there is none. */
    private val ids: MutableMap<String, String> by lazy { readJsonFile<MutableMap<String, String>>(cacheFile) { mutableMapOf() } }
    private val streams = mutableMapOf<String, Pair<BannerStreams, Long>>()

    private val youtubePattern = Regex("""(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|v/)|youtu\.be/)([\w-]{11})""")

    fun youtubeIdOf(url: String?): String? = url?.let { youtubePattern.find(it)?.groupValues?.get(1) }

    fun youtubeUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    internal fun clearCache() {
        synchronized(ids) { ids.clear() }
        cacheFile.delete()
        Cinemeta.clearCache()
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
        val details = Cinemeta.meta(response) ?: return null
        return details.path("trailerStreams").firstNotNullOfOrNull { it.path("ytId").asText().takeIf { s -> s.length == 11 } }
            ?: details.path("trailers").firstNotNullOfOrNull { it.path("source").asText().takeIf { s -> s.length == 11 } }
    }

    /** How a dubbed trailer is searched and recognised on YouTube, per interface language. */
    internal data class LanguageHints(
        val query: String,
        val markers: List<String>,
        val excluded: List<String>,
        val tmdb: String,
        /** One of them must be in the video title: a trailer, not an excerpt or a review. */
        val trailerWords: List<String>,
    )

    private val notTrailer = listOf("extrait", "clip", "critique", "film complet", "reaction", "review", "explained", "making of")

    internal val languageHints = mapOf(
        "fr" to LanguageHints(
            "bande annonce vf", listOf("vf", "francais", "francaise", "french", "version francaise"),
            listOf("vost", "vostfr", "sous-titre", "sous titre") + notTrailer, "fr", listOf("bande annonce", "trailer", "teaser"),
        ),
        "es" to LanguageHints(
            "trailer español", listOf("espanol", "castellano", "latino", "doblado"),
            listOf("subtitulado", "vose", "sub espanol", "pelicula completa") + notTrailer, "es", listOf("trailer", "avance", "teaser"),
        ),
        "de" to LanguageHints("trailer deutsch", listOf("deutsch", "german"), listOf("omu", "untertitel", "ganzer film") + notTrailer, "de", listOf("trailer", "teaser")),
        "it" to LanguageHints("trailer italiano", listOf("italiano", "ita"), listOf("sub ita", "sottotitoli", "film completo") + notTrailer, "it", listOf("trailer", "teaser")),
        "pt" to LanguageHints("trailer dublado", listOf("dublado", "portugues", "dub"), listOf("legendado", "leg", "filme completo") + notTrailer, "pt", listOf("trailer", "teaser")),
        "ar" to LanguageHints("إعلان مدبلج", listOf("مدبلج", "عربي", "arabic"), listOf("مترجم") + notTrailer, "ar", listOf("إعلان", "اعلان", "trailer")),
    )

    /** Lower case without accents, keeping non-latin letters (Arabic markers). */
    private fun fold(text: String) = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    internal data class SearchEntry(val id: String, val title: String, val durationSeconds: Double?)

    /**
     * The first YouTube result that is clearly a trailer of [filmTitle] in the wanted language:
     * the title words are all there, a language marker is present, no subtitled marker, short video.
     */
    internal fun pickLocalized(entries: List<SearchEntry>, filmTitle: String, language: String): String? =
        localizedCandidates(entries, filmTitle, language).firstOrNull()

    /** Every result that passes [pickLocalized]'s checks, in the search order. */
    internal fun localizedCandidates(entries: List<SearchEntry>, filmTitle: String, language: String): List<String> {
        val hints = languageHints[language] ?: return emptyList()
        val wanted = Recommender.normalizedTitle(Cinemeta.cleanTitle(filmTitle)).split(' ').filter { it.isNotBlank() }
        return entries.filter { entry ->
            val words = " " + fold(entry.title) + " "
            val latin = " " + Recommender.normalizedTitle(entry.title) + " "
            entry.id.length == 11 &&
                (entry.durationSeconds == null || entry.durationSeconds in 20.0..360.0) &&
                wanted.all { " $it " in latin } &&
                hints.markers.any { " ${fold(it)} " in words } &&
                hints.trailerWords.any { " ${fold(it)} " in words } &&
                hints.excluded.none { " ${fold(it)} " in words }
        }.map { it.id }.distinct()
    }

    /**
     * Trailer in the interface language when one is found (TMDB with a key, otherwise a checked
     * YouTube search), else the original version ([youtubeId]).
     */
    suspend fun localizedYoutubeId(response: LoadResponse, language: String = com.moncloudi.core.Store.settings.language): String? {
        val hints = languageHints[language]
        if (hints != null) {
            val key = "$language:" + Recommender.normalizedTitle(response.name) + "|" + (response.year ?: "")
            val cached = synchronized(ids) { ids[key] }
            val found = cached ?: try {
                withContext(Dispatchers.IO) { tmdbLocalized(response, hints) ?: youtubeLocalized(response, language) }.orEmpty()
                    .also { id -> synchronized(ids) { ids[key] = id; runCatching { writeJsonFile(cacheFile, ids) } } }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.info("Bande-annonce ($language) introuvable pour ${response.name} : ${t.message}")
                ""
            }
            if (found.isNotEmpty()) return found
        }
        return youtubeId(response)
    }

    private suspend fun tmdbLocalized(response: LoadResponse, hints: LanguageHints): String? {
        val key = com.moncloudi.core.Store.settings.tmdbApiKey.trim().ifEmpty { return null }
        val meta = runCatching { Cinemeta.meta(response) }.getOrNull()
        val id = meta?.path("moviedb_id")?.asInt(0)?.takeIf { it > 0 } ?: return null
        val type = if (Cinemeta.typeOf(response) == "movie") "movie" else "tv"
        val videos = mapper.readTree(app.get("${Enrichment.tmdbBase}/$type/$id/videos?api_key=$key&language=${hints.tmdb}", timeout = 15).text)
            .path("results").filter { it.path("site").asText() == "YouTube" && it.path("key").asText().length == 11 }
        return (videos.firstOrNull { it.path("type").asText() == "Trailer" } ?: videos.firstOrNull { it.path("type").asText() == "Teaser" })
            ?.path("key")?.asText()
    }

    private suspend fun youtubeLocalized(response: LoadResponse, language: String): String? {
        val hints = languageHints[language] ?: return null
        val ytDlp = ytDlpOrDownload() ?: return null
        val title = Cinemeta.cleanTitle(response.name)
        val query = listOfNotNull(title, response.year?.toString(), hints.query).joinToString(" ")
        val process = ProcessBuilder(
            ytDlp, "--encoding", "utf-8", "--flat-playlist", "--no-warnings", "--print", "%(id)s\t%(duration)s\t%(title)s", "ytsearch8:$query",
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
            .start()
        // A few short lines: they fit in the pipe buffer, so wait first (with a timeout).
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().readText()
        val entries = output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) null else SearchEntry(parts[0].trim(), parts[2].trim(), parts[1].trim().toDoubleOrNull())
        }.toList()
        // Search results can be blocked in the country or removed: keep the first one that plays
        // (its streams stay cached for the banner).
        return localizedCandidates(entries, title, language).take(3).firstOrNull { bannerStreams(it) != null }
    }

    /** yt-dlp shipped with the app, downloaded by it, or installed on the system. */
    fun ytDlp(): String? {
        val exe = if (isWindows) "yt-dlp.exe" else "yt-dlp"
        System.getProperty("compose.application.resources.dir")?.let { File(it, "yt-dlp/$exe") }?.takeIf { it.isFile }
            ?.let { return com.moncloudi.core.Bundled.runnable(it).path }
        OnDemand.installed(OnDemand.Tool.YTDLP)?.let { return it.path }
        return System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, exe) }?.firstOrNull { it.isFile }?.path
    }

    /** yt-dlp, downloaded the first time a trailer is wanted (and refreshed when old: YouTube changes). */
    suspend fun ytDlpOrDownload(): String? {
        if (OnDemand.outdated(OnDemand.Tool.YTDLP)) {
            runCatching { OnDemand.ensure(OnDemand.Tool.YTDLP, refresh = true) }.onFailure { Logger.info("yt-dlp : ${it.message}") }
        }
        ytDlp()?.let { return it }
        return runCatching { OnDemand.ensure(OnDemand.Tool.YTDLP).path }.onFailure { Logger.info("yt-dlp : ${it.message}") }.getOrNull()
    }

    /** Streams of a trailer for the home banner: the picture, and its sound played aside when wanted. */
    data class BannerStreams(val video: String, val audio: String?)

    /** Direct streams (720p at most) of a YouTube trailer, for the home banner. */
    suspend fun bannerStreams(youtubeId: String): BannerStreams? = withContext(Dispatchers.IO) {
        synchronized(streams) { streams[youtubeId] }?.takeIf { System.currentTimeMillis() - it.second < 3600_000L }?.let { return@withContext it.first }
        val ytDlp = ytDlpOrDownload() ?: return@withContext null
        // Separate video and audio (two URLs), or one file with both (one URL, used for both).
        val process = ProcessBuilder(
            ytDlp, "-g", "--no-warnings", "--no-playlist",
            "-f", "bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=720]+ba/b[height<=720]/b",
            youtubeUrl(youtubeId),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        // The output is a couple of URLs: it fits in the pipe buffer, so wait first (with a timeout).
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext null
        }
        val urls = process.inputStream.bufferedReader().readText().lineSequence().map { it.trim() }.filter { it.startsWith("http") }.toList()
        if (urls.isEmpty()) {
            Logger.info("yt-dlp n'a pas trouvé la bande-annonce $youtubeId")
            return@withContext null
        }
        BannerStreams(urls[0], urls.getOrNull(1) ?: urls[0])
            .also { synchronized(streams) { streams[youtubeId] = it to System.currentTimeMillis() } }
    }
}
