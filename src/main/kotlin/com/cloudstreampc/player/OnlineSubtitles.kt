package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.cloudstreampc.providers.Cinemeta
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app

/** An online subtitle: its address, ISO 639-2 language and a name for the player's menu. */
data class OnlineSubtitle(val url: String, val lang: String, val title: String)

/**
 * Subtitles from OpenSubtitles (through Stremio's public OpenSubtitles addon, no account),
 * found with the IMDb id of the title, in the languages chosen in the player settings.
 */
object OnlineSubtitles {
    internal var base = "https://opensubtitles-v3.strem.io"

    /** Settings use "fr,en"; OpenSubtitles uses ISO 639-2 codes. */
    private val codes = mapOf(
        "fr" to "fre", "fre" to "fre", "fra" to "fre", "french" to "fre", "français" to "fre",
        "en" to "eng", "eng" to "eng", "english" to "eng", "anglais" to "eng",
        "es" to "spa", "spa" to "spa", "de" to "ger", "ger" to "ger", "deu" to "ger", "it" to "ita", "ita" to "ita",
        "pt" to "por", "por" to "por", "nl" to "nld", "ar" to "ara", "ara" to "ara", "tr" to "tur", "pl" to "pol",
    )
    private val names = mapOf("fre" to "Français", "eng" to "Anglais", "spa" to "Espagnol", "ger" to "Allemand", "ita" to "Italien", "por" to "Portugais")

    fun wantedLanguages(setting: String = Store.settings.subtitleLanguages): List<String> =
        setting.split(",").map { it.trim().lowercase() }.mapNotNull { codes[it] ?: it.takeIf { c -> c.length == 3 } }.distinct()
            .ifEmpty { listOf("fre", "eng") }

    suspend fun find(response: LoadResponse, playable: Playable, perLanguage: Int = 5): List<OnlineSubtitle> {
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
        return wantedLanguages().flatMap { lang ->
            subtitles.filter { it.path("lang").asText() == lang }.take(perLanguage).mapIndexed { i, sub ->
                OnlineSubtitle(sub.path("url").asText(), lang, "OpenSubtitles · ${names[lang] ?: lang} ${i + 1}")
            }
        }.filter { it.url.startsWith("http") }
    }
}
