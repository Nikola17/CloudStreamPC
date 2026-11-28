package com.moncloudi.player

import com.moncloudi.core.AppDirs
import com.moncloudi.core.Logger
import com.moncloudi.core.Store
import com.moncloudi.core.mapper
import com.moncloudi.providers.Cinemeta
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** An online subtitle: its address, ISO 639-2 language and a name for the player's menu. */
data class OnlineSubtitle(val url: String, val lang: String, val title: String)

/**
 * Subtitles from OpenSubtitles (through Stremio's public OpenSubtitles addon, no account),
 * found with the IMDb id of the title, in the languages chosen in the player settings,
 * plus local caching so mpv switches subtitles instantaneously without video-host header conflicts.
 */
object OnlineSubtitles {
    internal var base = "https://opensubtitles-v3.strem.io"

    /** Settings use "fr,en"; OpenSubtitles uses ISO 639-2 codes. */
    private val codes = mapOf(
        "fr" to "fre", "fre" to "fre", "fra" to "fre", "french" to "fre", "français" to "fre", "francais" to "fre", "vf" to "fre", "vostfr" to "fre",
        "en" to "eng", "eng" to "eng", "english" to "eng", "anglais" to "eng", "vo" to "eng",
        "es" to "spa", "spa" to "spa", "spanish" to "spa", "espagnol" to "spa",
        "de" to "ger", "ger" to "ger", "deu" to "ger", "german" to "ger", "allemand" to "ger",
        "it" to "ita", "ita" to "ita", "italian" to "ita", "italien" to "ita",
        "pt" to "por", "por" to "por", "portuguese" to "por", "portugais" to "por",
        "nl" to "nld", "nld" to "nld", "dut" to "nld",
        "ar" to "ara", "ara" to "ara", "arabic" to "ara", "arabe" to "ara",
        "tr" to "tur", "tur" to "tur", "pl" to "pol", "pol" to "pol",
        "ja" to "jpn", "jpn" to "jpn", "japanese" to "jpn", "japonais" to "jpn",
    )
    private val names = mapOf(
        "fre" to "Français", "eng" to "Anglais", "spa" to "Espagnol", "ger" to "Allemand",
        "ita" to "Italien", "por" to "Portugais", "ara" to "Arabe", "nld" to "Néerlandais",
        "tur" to "Turc", "pol" to "Polonais", "jpn" to "Japonais",
    )

    fun inferLangCode(raw: String?): String {
        val clean = raw.orEmpty().trim().lowercase()
        if (clean.isBlank()) return "und"
        codes[clean]?.let { return it }
        val tokens = clean.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            codes[token]?.let { return it }
        }
        return clean.take(3).takeIf { it.all { c -> c in 'a'..'z' } && it.length in 2..3 } ?: "und"
    }

    fun displayLanguage(code: String?): String {
        val norm = inferLangCode(code)
        return names[norm] ?: code?.takeIf { it.isNotBlank() } ?: "Inconnu"
    }

    fun wantedLanguages(setting: String = Store.settings.subtitleLanguages, includeEnglishFallback: Boolean = false): List<String> {
        val primary = setting.split(",").map { it.trim().lowercase() }
            .mapNotNull { codes[it] ?: it.takeIf { c -> c.length == 3 } }
            .distinct()
            .ifEmpty { listOf("fre", "eng") }
        return if (includeEnglishFallback && "eng" !in primary) primary + "eng" else primary
    }

    suspend fun find(
        response: LoadResponse,
        playable: Playable,
        perLanguage: Int = 5,
        includeEnglishFallback: Boolean = false,
    ): List<OnlineSubtitle> {
        val imdb = runCatching { Cinemeta.imdbId(response) }.getOrNull() ?: return emptyList()
        val type = Cinemeta.typeOf(response)
        val id = if (type == "series") "$imdb:${playable.season ?: 1}:${playable.episode ?: 1}" else imdb
        val subtitles = try {
            mapper.readTree(app.get("$base/subtitles/$type/$id.json", timeout = 15).text).path("subtitles").toList()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.info("Sous-titres en ligne indisponibles : ${t.message}")
            return emptyList()
        }
        val langs = wantedLanguages(includeEnglishFallback = includeEnglishFallback)
        return langs.flatMap { lang ->
            val limit = if (lang == langs.first()) perLanguage else minOf(perLanguage, 3)
            subtitles.filter { it.path("lang").asText() == lang }.take(limit).mapIndexed { i, sub ->
                OnlineSubtitle(sub.path("url").asText(), lang, "OpenSubtitles · ${names[lang] ?: lang} ${i + 1}")
            }
        }.filter { it.url.startsWith("http") }
    }

    /**
     * Downloads a remote subtitle file into a local cache folder (with clean headers, avoiding
     * leaking video-host Referer/Origin headers to subtitle CDNs) so mpv switches subtitles
     * instantaneously and reliably. Returns the original URL if caching fails.
     */
    fun cacheSubtitle(url: String, headers: Map<String, String> = emptyMap()): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        return runCatching {
            val dir = File(AppDirs.cache, "subtitles").apply { mkdirs() }
            val ext = Regex("""\.(srt|vtt|ass|ssa|sub)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)?.lowercase() ?: "srt"
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            val file = File(dir, "$hash.$ext")
            if (file.isFile && file.length() > 16) return file.absolutePath

            val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> if (!k.equals("user-agent", true)) reqBuilder.header(k, v) }
            val client = app.baseClient.newBuilder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return url
                val bytes = resp.body.bytes()
                if (bytes.size < 10) return url
                // Detect actual VTT content even when URL ends without extension.
                val head = String(bytes, 0, minOf(bytes.size, 32), Charsets.UTF_8)
                val target = if (head.contains("WEBVTT") && ext != "vtt") File(dir, "$hash.vtt") else file
                target.writeBytes(bytes)
                pruneCache(dir)
                target.absolutePath
            }
        }.getOrElse { url }
    }

    private fun pruneCache(dir: File) {
        runCatching {
            val files = dir.listFiles()?.takeIf { it.size > 120 } ?: return
            files.sortedBy { it.lastModified() }.take(files.size - 80).forEach { it.delete() }
        }
    }
}
