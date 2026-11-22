package com.moncloudi.providers

import com.moncloudi.core.Logger
import com.moncloudi.core.Recommender
import com.moncloudi.core.Store
import com.moncloudi.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln

data class Person(val name: String, val role: String? = null, val photo: String? = null)

data class SimilarTitle(
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
)

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
 * Richer details pages: Cinemeta (no account) gives the IMDb rating, director, logo, background,
 * episode pictures AND content-similar recommendations (scored by shared genres, director, cast,
 * era and rating — plus Jikan recommendations for anime); with a free TMDB API key, localized French
 * TMDB recommendations, cast photos and French episode summaries are used too.
 */
object Enrichment {
    internal var tmdbBase = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p"
    private val cache = mutableMapOf<String, Enriched>()
    /** Cached Cinemeta genre catalogs ("movie|Action" -> list of metas), so recommendations are fast. */
    private val genreCatalogCache = ConcurrentHashMap<String, List<JsonNode>>()

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
        // Always ensure relevant similar recommendations exist even without a TMDB API key!
        if (enriched.similar.size < 6 && response.type != TvType.Live) {
            val fallback = runCatching { zeroConfigSimilar(response, meta) }
                .onFailure { Logger.info("Similaires : ${it.message}") }
                .getOrDefault(emptyList())
            if (fallback.isNotEmpty()) {
                val merged = (enriched.similar + fallback).distinctBy { Recommender.normalizedTitle(it.title) }.take(20)
                enriched = enriched.copy(similar = merged)
            }
        }
        // Nothing older than 1990 among the suggestions.
        enriched = enriched.copy(similar = enriched.similar.filter { Recommender.recentEnough(it.year) })
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

    /**
     * Relevant similar titles without requiring any API key:
     * - Anime with MAL id: official Jikan community recommendations.
     * - Movies & series: multi-genre Cinemeta catalog lookup scored by shared genres (Jaccard + count),
     *   same director, shared cast, era proximity, and IMDb rating.
     */
    private suspend fun zeroConfigSimilar(response: LoadResponse, meta: JsonNode?): List<SimilarTitle> = coroutineScope {
        val isAnime = response.type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
        if (isAnime) {
            val malId = response.syncData["MAL"]?.toIntOrNull()
            if (malId != null) {
                val jikan = runCatching { fetchJikanRecommendations(malId) }.getOrDefault(emptyList())
                if (jikan.size >= 6) return@coroutineScope jikan
            }
        }
        val type = Cinemeta.typeOf(response)
        val targetId = meta?.path("id")?.asText()?.takeIf { it.isNotBlank() }
        val targetTitle = Recommender.normalizedTitle(response.name)
        val targetGenres = (meta?.path("genres")?.map { it.asText() }.orEmpty().ifEmpty {
            meta?.path("genre")?.map { it.asText() }.orEmpty()
        } + response.tags.orEmpty().mapNotNull(::mapFrenchGenreToCinemeta)).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targetGenres.isEmpty()) return@coroutineScope emptyList()

        val targetDirectors = meta?.path("director")?.map { it.asText().lowercase() }.orEmpty().toSet()
        val targetCast = (meta?.path("cast")?.map { it.asText().lowercase() }.orEmpty() +
            response.actors?.map { it.actor.name.lowercase() }.orEmpty()).toSet()
        val targetYear = response.year ?: meta?.path("releaseInfo")?.asText()?.take(4)?.toIntOrNull()

        val catalogs = targetGenres.take(3).map { genre ->
            async { fetchCinemetaGenreCatalog(type, genre) }
        }.awaitAll().flatten()

        rankSimilarNodes(
            candidates = catalogs,
            targetId = targetId,
            targetNormalizedTitle = targetTitle,
            targetGenres = targetGenres.map { it.lowercase() }.toSet(),
            targetDirectors = targetDirectors,
            targetCast = targetCast,
            targetYear = targetYear,
        )
    }

    private suspend fun fetchJikanRecommendations(malId: Int): List<SimilarTitle> {
        val root = mapper.readTree(app.get("https://api.jikan.moe/v4/anime/$malId/recommendations", timeout = 12).text)
        return root.path("data").take(20).mapNotNull { rec ->
            val entry = rec.path("entry")
            val title = entry.path("title").asText().ifBlank { return@mapNotNull null }
            val poster = entry.path("images").path("jpg").path("large_image_url").asText()
                .ifBlank { entry.path("images").path("jpg").path("image_url").asText() }
                .takeIf { it.startsWith("http") }
            SimilarTitle(title, poster, null, null, listOf("Anime"))
        }
    }

    private suspend fun fetchCinemetaGenreCatalog(type: String, genre: String): List<JsonNode> {
        val cacheKey = "$type|${genre.lowercase()}"
        genreCatalogCache[cacheKey]?.let { return it }
        val encoded = URLEncoder.encode(genre, Charsets.UTF_8).replace("+", "%20")
        val metas = runCatching {
            mapper.readTree(app.get("${Cinemeta.base}/catalog/$type/top/genre=$encoded.json", timeout = 12).text)
                .path("metas").toList()
        }.getOrDefault(emptyList())
        if (metas.isNotEmpty()) genreCatalogCache[cacheKey] = metas
        return metas
    }

    internal fun rankSimilarNodes(
        candidates: List<JsonNode>,
        targetId: String?,
        targetNormalizedTitle: String,
        targetGenres: Set<String>,
        targetDirectors: Set<String>,
        targetCast: Set<String>,
        targetYear: Int?,
        limit: Int = 18,
    ): List<SimilarTitle> {
        val seen = HashSet<String>()
        return candidates.mapNotNull { node ->
            val id = node.path("id").asText()
            val title = node.path("name").asText().ifBlank { return@mapNotNull null }
            val norm = Recommender.normalizedTitle(title)
            if (id.isNotBlank() && id == targetId) return@mapNotNull null
            if (norm == targetNormalizedTitle || norm.isEmpty()) return@mapNotNull null
            if (!seen.add(id.ifBlank { norm }) || !seen.add("t:$norm")) return@mapNotNull null

            val genres = node.path("genres").map { it.asText() }.filter { it.isNotBlank() }
                .ifEmpty { node.path("genre").map { it.asText() }.filter { it.isNotBlank() } }
            val genreSet = genres.map { it.lowercase() }.toSet()
            val sharedGenres = genreSet.intersect(targetGenres).size
            val unionGenres = genreSet.union(targetGenres).size.coerceAtLeast(1)
            val jaccard = sharedGenres.toDouble() / unionGenres

            val directors = node.path("director").map { it.asText().lowercase() }.toSet()
            val sharedDirectors = directors.intersect(targetDirectors).size
            val cast = node.path("cast").map { it.asText().lowercase() }.toSet()
            val sharedCast = cast.intersect(targetCast).size

            val year = node.path("releaseInfo").asText().take(4).toIntOrNull()
            val eraBonus = when {
                targetYear == null || year == null -> 0.0
                abs(targetYear - year) <= 6 -> 1.2
                abs(targetYear - year) <= 12 -> 0.6
                else -> 0.0
            }
            val rating = node.path("imdbRating").asText().toDoubleOrNull()?.takeIf { it > 0.0 }
            val ratingBonus = (rating ?: 6.2) * 0.35
            val pop = node.path("popularities").path("moviedb").asDouble(node.path("popularity").asDouble(0.0))
            val popBonus = ln(1.0 + pop.coerceAtLeast(0.0)) * 0.18

            val score = (sharedGenres * 3.5) + (jaccard * 4.5) + (sharedDirectors * 5.0) +
                (sharedCast * 2.0) + eraBonus + ratingBonus + popBonus

            val poster = node.path("poster").asText().takeIf { it.startsWith("http") }
                ?.replace("/poster/small/", "/poster/medium/")
            score to SimilarTitle(title, poster, year, rating, genres.take(2))
        }.sortedByDescending { it.first }.take(limit).map { it.second }
    }

    private fun mapFrenchGenreToCinemeta(tag: String): String? = when (tag.trim().lowercase()) {
        "action" -> "Action"
        "aventure", "adventure" -> "Adventure"
        "animation", "animé", "anime" -> "Animation"
        "biographie", "biopic", "biography" -> "Biography"
        "comédie", "comedie", "comedy" -> "Comedy"
        "crime", "policier" -> "Crime"
        "documentaire", "documentary" -> "Documentary"
        "drame", "drama" -> "Drama"
        "famille", "familial", "family" -> "Family"
        "fantastique", "fantasy" -> "Fantasy"
        "histoire", "historique", "history" -> "History"
        "horreur", "épouvante", "epouvante", "horror" -> "Horror"
        "musique", "musical", "music" -> "Music"
        "mystère", "mystere", "mystery" -> "Mystery"
        "romance", "romantique" -> "Romance"
        "science-fiction", "sci-fi", "sf" -> "Sci-Fi"
        "sport", "sports" -> "Sport"
        "thriller", "suspense" -> "Thriller"
        "guerre", "war" -> "War"
        "western" -> "Western"
        else -> null
    }

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
        val details = tmdb("$type/$id?append_to_response=credits,recommendations,similar,${if (type == "movie") "release_dates" else "content_ratings"}", key)
        val certification = certificationOf(details, type)
        val cast = details.path("credits").path("cast").take(20).map {
            Person(
                it.path("name").asText(),
                it.path("character").asText().ifBlank { null },
                it.path("profile_path").asText().takeIf { p -> p.startsWith("/") }?.let { p -> "$IMAGES/w185$p" },
            )
        }
        fun parseTmdbList(node: JsonNode) = node.path("results").mapNotNull {
            val title = it.path("title").asText().ifBlank { it.path("name").asText() }.ifBlank { return@mapNotNull null }
            val voteAvg = it.path("vote_average").asDouble(0.0).takeIf { v -> v > 0.0 }
            SimilarTitle(
                title = title,
                posterUrl = it.path("poster_path").asText().takeIf { p -> p.startsWith("/") }?.let { p -> "$IMAGES/w342$p" },
                year = (it.path("release_date").asText().ifBlank { it.path("first_air_date").asText() }).take(4).toIntOrNull(),
                rating = voteAvg,
            )
        }
        // Blend TMDB recommendations (collaborative) first, then similar (content-based), deduplicated.
        val similar = (parseTmdbList(details.path("recommendations")) + parseTmdbList(details.path("similar")))
            .distinctBy { Recommender.normalizedTitle(it.title) }
            .take(20)
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

    fun clear() {
        synchronized(cache) { cache.clear() }
        genreCatalogCache.clear()
    }
}
