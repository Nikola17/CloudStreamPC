package com.cloudstreampc.providers

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * Curated Top 10 of recently released, popular, and critically well-rated movies or series.
 *
 * Blends three signals instead of blindly ranking the latest uploaded items:
 * - Recency (strictly already released within the last 12-18 months, favoring very recent releases)
 * - Popularity (TMDB / Trakt / Stremio popularity signals)
 * - Rating (IMDb / TMDB rating, filtering out poorly rated titles)
 */
object TopRecentRanking {
    data class Item(
        val id: String,
        val title: String,
        val posterUrl: String?,
        val year: Int?,
        val rating: Double?,
        val releasedAt: LocalDate?,
        val genres: List<String>,
        val popularity: Double,
        val score: Double,
        val type: String, // "movie" or "series"
    )

    private data class CacheEntry(val loadedAt: Long, val items: List<Item>)
    private const val CACHE_TTL_MS = 2 * 3_600_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    fun clearCache() = cache.clear()

    suspend fun top10(type: String = "movie", today: LocalDate = LocalDate.now()): List<Item> {
        val now = System.currentTimeMillis()
        cache[type]?.takeIf { now - it.loadedAt < CACHE_TTL_MS }?.let { return it.items }
        val ranked = withContext(Dispatchers.IO) {
            runCatching { fetchAndRank(type, today) }
                .onFailure { Logger.info("Top 10 ($type) indisponible : ${it.message}") }
                .getOrDefault(emptyList())
        }
        if (ranked.isNotEmpty()) cache[type] = CacheEntry(now, ranked)
        return ranked
    }

    private suspend fun fetchAndRank(type: String, today: LocalDate): List<Item> = coroutineScope {
        val tmdbKey = Store.settings.tmdbApiKey.trim()
        // If user has a TMDB key, pull French localized trending + now_playing/on_the_air directly from TMDB.
        if (tmdbKey.isNotEmpty()) {
            val tmdbItems = runCatching { fetchFromTmdb(type, tmdbKey, today) }.getOrDefault(emptyList())
            if (tmdbItems.size >= 10) return@coroutineScope tmdbItems.take(10)
        }
        // Zero-config: Cinemeta carries exact release date, IMDb rating, and TMDB/Trakt/Stremio popularities.
        val urls = listOf(
            "${Cinemeta.base}/catalog/$type/top.json",
            "${Cinemeta.base}/catalog/$type/top/skip=50.json",
            "${Cinemeta.base}/catalog/$type/year.json",
        )
        val rawNodes = urls.map { url ->
            async {
                runCatching { mapper.readTree(app.get(url, timeout = 15).text).path("metas").toList() }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        rankCinemetaNodes(rawNodes, type, today).take(10)
    }

    internal fun rankCinemetaNodes(nodes: List<JsonNode>, type: String, today: LocalDate = LocalDate.now()): List<Item> {
        val seen = HashSet<String>()
        val parsed = mutableListOf<Item>()
        for (node in nodes) {
            val id = node.path("id").asText().ifBlank { continue }
            val title = node.path("name").asText().ifBlank { continue }
            val normKey = Recommender.normalizedTitle(title)
            if (!seen.add(id) || !seen.add("t:$normKey")) continue

            val released = parseReleaseDate(node.path("released").asText(), node.path("releaseInfo").asText())
            val year = released?.year ?: node.path("releaseInfo").asText().take(4).toIntOrNull()
            val rating = node.path("imdbRating").asText().toDoubleOrNull()?.takeIf { it > 0.0 }
            val popNode = node.path("popularities")
            val moviedbPop = popNode.path("moviedb").asDouble(0.0)
            val traktPop = popNode.path("trakt").asDouble(0.0)
            val stremioPop = popNode.path("stremio").asDouble(node.path("popularity").asDouble(0.0))
            val combinedPop = moviedbPop + (traktPop / 15.0) + (stremioPop * 4.0)
            val poster = node.path("poster").asText().takeIf { it.startsWith("http") }
                ?.replace("/poster/small/", "/poster/medium/")
            val genres = node.path("genres").map { it.asText() }.filter { it.isNotBlank() }
                .ifEmpty { node.path("genre").map { it.asText() }.filter { it.isNotBlank() } }

            parsed += Item(
                id = id,
                title = title,
                posterUrl = poster,
                year = year,
                rating = rating,
                releasedAt = released,
                genres = genres,
                popularity = combinedPop,
                score = 0.0,
                type = type,
            )
        }
        return rankCandidates(parsed, today)
    }

    private suspend fun fetchFromTmdb(type: String, key: String, today: LocalDate): List<Item> = coroutineScope {
        val tmdbType = if (type == "movie") "movie" else "tv"
        val paths = if (type == "movie") {
            listOf("trending/movie/week", "movie/now_playing?region=FR", "movie/popular?region=FR")
        } else {
            listOf("trending/tv/week", "tv/on_the_air", "tv/popular")
        }
        val raw = paths.map { path ->
            async {
                val sep = if ('?' in path) '&' else '?'
                runCatching {
                    mapper.readTree(app.get("${Enrichment.tmdbBase}/$path${sep}api_key=$key&language=fr-FR", timeout = 15).text)
                        .path("results").toList()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        val seen = HashSet<String>()
        val candidates = raw.mapNotNull { node ->
            val id = node.path("id").asText().ifBlank { return@mapNotNull null }
            if (!seen.add(id)) return@mapNotNull null
            val title = node.path("title").asText().ifBlank { node.path("name").asText() }.ifBlank { return@mapNotNull null }
            val dateStr = node.path("release_date").asText().ifBlank { node.path("first_air_date").asText() }
            val released = parseReleaseDate(dateStr, null)
            val votes = node.path("vote_count").asInt(0)
            val rating = node.path("vote_average").asDouble(0.0).takeIf { it > 0.0 && votes >= 25 }
            val pop = node.path("popularity").asDouble(0.0)
            val poster = node.path("poster_path").asText().takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }
            Item(
                id = "tmdb:$id",
                title = title,
                posterUrl = poster,
                year = released?.year,
                rating = rating,
                releasedAt = released,
                genres = emptyList(),
                popularity = pop,
                score = 0.0,
                type = type,
            )
        }
        rankCandidates(candidates, today)
    }

    /**
     * Filters to genuinely released recent titles and scores them with a balanced blend of
     * recency (40%), popularity (35%), and rating (25%).
     */
    internal fun rankCandidates(candidates: List<Item>, today: LocalDate = LocalDate.now()): List<Item> {
        fun filterWindow(maxAgeDays: Long, minRating: Double): List<Item> = candidates.mapNotNull { item ->
            val released = item.releasedAt
            val daysAgo = when {
                released != null -> ChronoUnit.DAYS.between(released, today)
                item.year != null -> {
                    if (item.year > today.year) return@mapNotNull null
                    if (item.year == today.year) 90L else ChronoUnit.DAYS.between(LocalDate.of(item.year, 7, 1), today)
                }
                else -> return@mapNotNull null
            }
            // Exclude unreleased future movies and titles older than the recency window.
            if (daysAgo < 0 || daysAgo > maxAgeDays) return@mapNotNull null
            val rating = item.rating ?: return@mapNotNull null
            if (rating < minRating) return@mapNotNull null
            val score = compositeScore(daysAgo, item.popularity, rating)
            item.copy(score = score)
        }.sortedByDescending { it.score }

        // Strict window first: released within 12 months & rated >= 6.4
        val strict = filterWindow(maxAgeDays = 365, minRating = 6.4)
        if (strict.size >= 10) return strict.take(10)
        // Relax slightly if early in the year or sparse catalog: 18 months & rated >= 6.0
        val relaxed = filterWindow(maxAgeDays = 550, minRating = 6.0)
        if (relaxed.size >= 10) return relaxed.take(10)
        return filterWindow(maxAgeDays = 730, minRating = 5.5).take(10)
    }

    /**
     * Composite score in [0, 1+]:
     * - Recency (40%): exponential decay with 180-day characteristic window
     * - Popularity (35%): log-scaled popularity normalized against 500
     * - Rating (25%): normalized [5.5 .. 9.0] plus a quality multiplier so low-rated hype never tops the list
     */
    internal fun compositeScore(daysAgo: Long, popularity: Double, rating: Double): Double {
        val recency = exp(-daysAgo.coerceAtLeast(0) / 180.0)
        val popNorm = (ln(1.0 + popularity.coerceAtLeast(0.0)) / ln(1.0 + 500.0)).coerceIn(0.0, 1.0)
        val ratingNorm = ((rating - 5.5) / 3.5).coerceIn(0.0, 1.0)
        val raw = 0.40 * recency + 0.35 * popNorm + 0.25 * ratingNorm
        val qualityGate = 0.65 + 0.35 * ratingNorm
        return raw * qualityGate
    }

    internal fun parseReleaseDate(isoOrDate: String?, releaseInfo: String?): LocalDate? {
        val text = isoOrDate?.trim().orEmpty()
        if (text.length >= 10) {
            runCatching { LocalDate.parse(text.substring(0, 10)) }.getOrNull()?.let { return it }
        }
        val year = releaseInfo?.trim()?.take(4)?.toIntOrNull()
            ?: text.take(4).toIntOrNull()
        return year?.let { LocalDate.of(it, 6, 15) }
    }
}
