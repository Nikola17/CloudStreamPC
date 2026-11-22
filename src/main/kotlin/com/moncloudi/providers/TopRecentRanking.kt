package com.moncloudi.providers

import com.moncloudi.core.Logger
import com.moncloudi.core.Recommender
import com.moncloudi.core.Store
import com.moncloudi.core.mapper
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
        /** Original title when [title] is the one of the interface language (searched too). */
        val originalTitle: String? = null,
    )

    private data class CacheEntry(val loadedAt: Long, val items: List<Item>)
    private const val CACHE_TTL_MS = 2 * 3_600_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    /** A film released less than this many days ago is taken as still in cinemas (no source yet). */
    const val CINEMA_DAYS = 90L

    /** Ranked candidates kept before the availability check picks the 10 that have a source. */
    const val POOL_SIZE = 40

    fun clearCache() = cache.clear()

    /** Best recent titles, more than 10, best first: [TopAvailability] keeps those with a source. */
    suspend fun candidates(type: String = "movie", today: LocalDate = LocalDate.now()): List<Item> {
        val now = System.currentTimeMillis()
        val language = Store.settings.language
        val cacheKey = "$type|$language"
        cache[cacheKey]?.takeIf { now - it.loadedAt < CACHE_TTL_MS }?.let { return it.items }
        val ranked = withContext(Dispatchers.IO) {
            runCatching { fetchAndRank(type, today, language) }
                .onFailure { Logger.info("Top 10 ($type) indisponible : ${it.message}") }
                .getOrDefault(emptyList())
        }
        if (ranked.isNotEmpty()) cache[cacheKey] = CacheEntry(now, ranked)
        return ranked
    }

    /** The 10 best, without checking that the addons have them. */
    suspend fun top10(type: String = "movie", today: LocalDate = LocalDate.now()): List<Item> = candidates(type, today).take(10)

    private suspend fun fetchAndRank(type: String, today: LocalDate, language: String): List<Item> = coroutineScope {
        val tmdbKey = Store.settings.tmdbApiKey.trim()
        // With a TMDB key: titles in the interface language, and the films now in cinemas left out.
        if (tmdbKey.isNotEmpty()) {
            val tmdbItems = runCatching { fetchFromTmdb(type, tmdbKey, today, language) }.getOrDefault(emptyList())
            if (tmdbItems.size >= 10) return@coroutineScope tmdbItems
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

        val ranked = rankCinemetaNodes(rawNodes, type, today)
        // Cinemeta titles are in English: the interface language's title (Wikidata) is what the addons use.
        val labels = runCatching { LocalizedTitles.of(ranked.map { it.id }.filter { it.startsWith("tt") }, language) }
            .onFailure { Logger.info("Titres localisés indisponibles : ${it.message}") }
            .getOrDefault(emptyMap())
        ranked.map { item ->
            val label = labels[item.id]?.takeIf { Recommender.normalizedTitle(it) != Recommender.normalizedTitle(item.title) }
            if (label == null) item else item.copy(title = label, originalTitle = item.title)
        }
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

    /** TMDB language and release region for the interface language. */
    private fun tmdbLocale(language: String) = when (language) {
        "fr" -> "fr-FR" to "FR"
        "es" -> "es-ES" to "ES"
        "de" -> "de-DE" to "DE"
        "it" -> "it-IT" to "IT"
        "pt" -> "pt-BR" to "BR"
        "ar" -> "ar" to "FR"
        else -> "en-US" to "US"
    }

    private suspend fun fetchFromTmdb(type: String, key: String, today: LocalDate, language: String): List<Item> = coroutineScope {
        val (tmdbLanguage, region) = tmdbLocale(language)
        val paths = if (type == "movie") {
            listOf("trending/movie/week", "movie/popular?region=$region", "movie/popular?region=$region&page=2")
        } else {
            listOf("trending/tv/week", "tv/on_the_air", "tv/popular")
        }
        suspend fun results(path: String): List<JsonNode> {
            val sep = if ('?' in path) '&' else '?'
            return runCatching {
                mapper.readTree(app.get("${Enrichment.tmdbBase}/$path${sep}api_key=$key&language=$tmdbLanguage", timeout = 15).text)
                    .path("results").toList()
            }.getOrDefault(emptyList())
        }
        // Films now in the country's cinemas: no source yet, left out.
        val inCinemas = if (type == "movie") {
            async { (results("movie/now_playing?region=$region") + results("movie/now_playing?region=$region&page=2")).map { it.path("id").asText() }.toSet() }
        } else null
        val raw = paths.map { path -> async { results(path) } }.awaitAll().flatten()
        val excluded = inCinemas?.await().orEmpty()

        val seen = HashSet<String>()
        val candidates = raw.mapNotNull { node ->
            val id = node.path("id").asText().ifBlank { return@mapNotNull null }
            if (!seen.add(id) || id in excluded) return@mapNotNull null
            val title = node.path("title").asText().ifBlank { node.path("name").asText() }.ifBlank { return@mapNotNull null }
            val original = node.path("original_title").asText().ifBlank { node.path("original_name").asText() }
                .takeIf { it.isNotBlank() && Recommender.normalizedTitle(it) != Recommender.normalizedTitle(title) }
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
                originalTitle = original,
            )
        }
        rankCandidates(candidates, today)
    }

    /**
     * Filters to genuinely released recent titles and scores them with a balanced blend of
     * recency (40%), popularity (35%), and rating (25%). Films released less than [CINEMA_DAYS]
     * ago are still in cinemas and left out. Best first: the 12-month window rated 6.4+, then
     * what the wider windows add, up to [limit].
     */
    internal fun rankCandidates(candidates: List<Item>, today: LocalDate = LocalDate.now(), limit: Int = POOL_SIZE): List<Item> {
        fun filterWindow(maxAgeDays: Long, minRating: Double): List<Item> = candidates.mapNotNull { item ->
            val released = item.releasedAt
            val daysAgo = when {
                released != null -> ChronoUnit.DAYS.between(released, today)
                item.year != null -> {
                    if (item.year > today.year) return@mapNotNull null
                    // Only the year: this year's films may still be in cinemas, last year's are out.
                    if (item.year == today.year) {
                        if (item.type == "movie") return@mapNotNull null
                        90L
                    } else ChronoUnit.DAYS.between(LocalDate.of(item.year, 7, 1), today)
                }
                else -> return@mapNotNull null
            }
            // Exclude unreleased future titles, films still in cinemas and titles older than the recency window.
            if (daysAgo < 0 || daysAgo > maxAgeDays) return@mapNotNull null
            if (item.type == "movie" && daysAgo < CINEMA_DAYS) return@mapNotNull null
            val rating = item.rating ?: return@mapNotNull null
            if (rating < minRating) return@mapNotNull null
            val score = compositeScore(daysAgo, item.popularity, rating)
            item.copy(score = score)
        }.sortedByDescending { it.score }

        // Strict window first: released within 12 months & rated >= 6.4; then 18 months & >= 6.0; then 24 months & >= 5.5.
        return (filterWindow(maxAgeDays = 365, minRating = 6.4) +
            filterWindow(maxAgeDays = 550, minRating = 6.0) +
            filterWindow(maxAgeDays = 730, minRating = 5.5))
            .distinctBy { it.id }
            .take(limit)
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
