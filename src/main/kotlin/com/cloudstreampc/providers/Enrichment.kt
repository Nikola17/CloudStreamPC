package com.cloudstreampc.providers

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

data class Person(val name: String, val role: String? = null, val photo: String? = null)

data class SimilarTitle(val title: String, val posterUrl: String?, val year: Int?)

data class EpisodeExtra(val title: String?, val overview: String?, val thumbnail: String?)

/** What a details page can add to what the extension gives. */
data class Enriched(
    val imdbRating: String? = null,
    val directors: List<String> = emptyList(),
    val awards: String? = null,
    val overview: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val cast: List<Person> = emptyList(),
    val similar: List<SimilarTitle> = emptyList(),
    /** By "season:episode". */
    val episodes: Map<String, EpisodeExtra> = emptyMap(),
    /** Age rating from TMDB (France first, then the United States). */
    val certification: String? = null,
) {
    fun episode(season: Int?, episode: Int?) = if (episode == null) null else episodes["${season ?: 1}:$episode"]
}

/**
 * Richer details pages: Cinemeta (no account) gives the IMDb rating, director, logo, background and
 * episode pictures; with a free TMDB API key, the cast with photos, similar titles and French
 * episode summaries too.
 */
object Enrichment {
    internal var tmdbBase = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p"
    private val cache = mutableMapOf<String, Enriched>()

    suspend fun of(response: LoadResponse): Enriched? {
        val key = response.apiName + "|" + response.url
        synchronized(cache) { cache[key] }?.let { return it }
        val meta = runCatching { Cinemeta.meta(response) }.getOrNull()
        var enriched = meta?.let(::fromCinemeta) ?: Enriched()
        val tmdbKey = Store.settings.tmdbApiKey.trim()
        if (tmdbKey.isNotEmpty()) {
            enriched = runCatching { withTmdb(enriched, response, meta, tmdbKey) }
                .onFailure { Logger.info("TMDB : ${it.message}") }.getOrDefault(enriched)
        }
        if (meta == null && enriched == Enriched()) return null
        synchronized(cache) { cache[key] = enriched }
        return enriched
    }

    internal fun fromCinemeta(meta: JsonNode): Enriched = Enriched(
        imdbRating = meta.path("imdbRating").asText().takeIf { it.isNotBlank() && it != "0" },
        directors = meta.path("director").map { it.asText() }.filter { it.isNotBlank() },
        awards = meta.path("awards").asText().takeIf { it.isNotBlank() },
        overview = meta.path("description").asText().takeIf { it.isNotBlank() },
        background = meta.path("background").asText().takeIf { it.startsWith("http") },
        logo = meta.path("logo").asText().takeIf { it.startsWith("http") },
        cast = meta.path("cast").map { Person(it.asText()) },
        episodes = meta.path("videos").filter { it.has("episode") || it.has("number") }.associate { video ->
            "${video.path("season").asInt(1)}:${video.path("episode").asInt(video.path("number").asInt())}" to EpisodeExtra(
                video.path("name").asText().ifBlank { null },
                video.path("overview").asText().ifBlank { video.path("description").asText() }.ifBlank { null },
                video.path("thumbnail").asText().takeIf { it.startsWith("http") },
            )
        },
    )

    private suspend fun tmdb(path: String, key: String): JsonNode {
        val separator = if ('?' in path) '&' else '?'
        return mapper.readTree(app.get("$tmdbBase/$path${separator}api_key=$key&language=fr-FR", timeout = 15).text)
    }

    private suspend fun withTmdb(base: Enriched, response: LoadResponse, meta: JsonNode?, key: String): Enriched = coroutineScope {
        val type = if (Cinemeta.typeOf(response) == "movie") "movie" else "tv"
        val id = meta?.path("moviedb_id")?.asInt(0)?.takeIf { it > 0 } ?: run {
            val query = URLEncoder.encode(Cinemeta.cleanTitle(response.name), Charsets.UTF_8)
            val yearParam = response.year?.let { if (type == "movie") "&year=$it" else "&first_air_date_year=$it" }.orEmpty()
            tmdb("search/$type?query=$query$yearParam", key).path("results").firstOrNull()?.path("id")?.asInt(0)?.takeIf { it > 0 }
        } ?: return@coroutineScope base
        val details = tmdb("$type/$id?append_to_response=credits,recommendations,${if (type == "movie") "release_dates" else "content_ratings"}", key)
        val certification = certificationOf(details, type)
        val cast = details.path("credits").path("cast").take(20).map {
            Person(
                it.path("name").asText(),
                it.path("character").asText().ifBlank { null },
                it.path("profile_path").asText().takeIf { p -> p.startsWith("/") }?.let { p -> "$IMAGES/w185$p" },
            )
        }
        val similar = details.path("recommendations").path("results").take(20).map {
            SimilarTitle(
                it.path("title").asText().ifBlank { it.path("name").asText() },
                it.path("poster_path").asText().takeIf { p -> p.startsWith("/") }?.let { p -> "$IMAGES/w342$p" },
                (it.path("release_date").asText().ifBlank { it.path("first_air_date").asText() }).take(4).toIntOrNull(),
            )
        }
        // French episode titles, summaries and pictures, season by season.
        val seasons = if (type == "tv") {
            details.path("seasons").map { it.path("season_number").asInt() }.filter { it > 0 }.take(30).map { number ->
                async {
                    runCatching { tmdb("tv/$id/season/$number", key) }.getOrNull()?.path("episodes")?.associate { ep ->
                        "$number:${ep.path("episode_number").asInt()}" to EpisodeExtra(
                            ep.path("name").asText().ifBlank { null },
                            ep.path("overview").asText().ifBlank { null },
                            ep.path("still_path").asText().takeIf { p -> p.startsWith("/") }?.let { p -> "$IMAGES/w300$p" },
                        )
                    }.orEmpty()
                }
            }.awaitAll().fold(emptyMap<String, EpisodeExtra>()) { acc, map -> acc + map }
        } else {
            emptyMap()
        }
        base.copy(
            overview = details.path("overview").asText().ifBlank { null } ?: base.overview,
            cast = cast.ifEmpty { base.cast },
            similar = similar,
            // TMDB in French first, Cinemeta (English) for what it lacks.
            episodes = base.episodes + seasons.mapValues { (k, v) ->
                val fallback = base.episodes[k]
                EpisodeExtra(v.title ?: fallback?.title, v.overview ?: fallback?.overview, v.thumbnail ?: fallback?.thumbnail)
            },
            background = base.background ?: details.path("backdrop_path").asText().takeIf { it.startsWith("/") }?.let { "$IMAGES/w1280$it" },
            certification = certification ?: base.certification,
        )
    }

    /** "release_dates" (films) or "content_ratings" (series) of TMDB: France, then the United States. */
    internal fun certificationOf(details: JsonNode, type: String): String? {
        val byCountry = if (type == "movie") {
            details.path("release_dates").path("results").associate { country ->
                country.path("iso_3166_1").asText() to country.path("release_dates").map { it.path("certification").asText() }.firstOrNull { it.isNotBlank() }
            }
        } else {
            details.path("content_ratings").path("results").associate { it.path("iso_3166_1").asText() to it.path("rating").asText().ifBlank { null } }
        }
        return listOf("FR", "US", "GB", "DE").firstNotNullOfOrNull { byCountry[it] }
    }

    fun clear() = synchronized(cache) { cache.clear() }
}
