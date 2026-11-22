package com.moncloudi.core

import kotlin.math.pow
import kotlin.math.sqrt

/** Computes recommendations from [TasteData] only (everything stays on the computer). */
object Recommender {
    data class Recommendation(val item: RecItem, val score: Double, val because: List<String>)
    data class BecauseRow(val seed: TasteSeed, val items: List<RecItem>)

    private const val HALF_LIFE_DAYS = 30.0

    /** Nothing released before this year is recommended (home rows and details pages). */
    const val MIN_YEAR = 1990

    /** Unknown years are kept: many extensions do not give one. */
    fun recentEnough(year: Int?) = year == null || year >= MIN_YEAR

    fun normalizedTitle(name: String) =
        java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\[(vf|vostfr|vf/vostfr|french|multi)]|\\((vf|vostfr)\\)"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Groups movie-like and series-like types, so watching series favours series. */
    fun family(type: String?) = when (type) {
        "Movie", "AnimeMovie", "Documentary", "Torrent" -> "movie"
        "TvSeries", "Anime", "OVA", "Cartoon", "AsianDrama" -> "series"
        "Live" -> "live"
        null -> null
        else -> "other"
    }

    /**
     * @param excluded "apiName|url" of titles already watched, in progress or favorite
     */
    fun recommend(
        taste: TasteData,
        excluded: Set<String>,
        now: Long = System.currentTimeMillis(),
        limit: Int = 30,
    ): List<Recommendation> {
        val seeds = taste.seeds.filter { it.weight > 0 }
        if (seeds.isEmpty()) return emptyList()

        fun recency(seed: TasteSeed) = 0.5.pow((now - seed.lastSeen).coerceAtLeast(0) / 86_400_000.0 / HALF_LIFE_DAYS)

        // Share of interest per family (movies / series / live).
        val familyWeight = mutableMapOf<String, Double>()
        seeds.forEach { s -> family(s.type)?.let { familyWeight.merge(it, s.weight * recency(s), Double::plus) } }
        val totalFamily = familyWeight.values.sum().takeIf { it > 0 } ?: 1.0

        val seedTitles = seeds.map { normalizedTitle(it.name) }.toSet()
        val seedKeys = seeds.map { Taste.key(it.apiName, it.url) }.toSet()

        class Candidate(var item: RecItem, var score: Double, val because: MutableSet<String>)
        val candidates = linkedMapOf<String, Candidate>()

        for (seed in seeds) {
            val seedScore = seed.weight * recency(seed)
            seed.recommendations.forEachIndexed { position, rec ->
                val key = Taste.key(rec.apiName, rec.url)
                val title = normalizedTitle(rec.name)
                if (title.isEmpty() || key in excluded || key in taste.dismissed || key in seedKeys || title in seedTitles) return@forEachIndexed
                if (!recentEnough(rec.year)) return@forEachIndexed
                // Earlier suggestions are the most similar ones.
                val contribution = seedScore / sqrt(position + 1.0)
                // The same title suggested by several providers is merged.
                val id = title + "|" + (rec.year ?: "")
                val candidate = candidates.getOrPut(id) { Candidate(rec, 0.0, linkedSetOf()) }
                candidate.score += contribution
                candidate.because += seed.name
                if (candidate.item.posterUrl == null && rec.posterUrl != null) candidate.item = rec
            }
        }

        return candidates.values.map { c ->
            val familyShare = family(c.item.type)?.let { (familyWeight[it] ?: 0.0) / totalFamily } ?: 0.3
            // Suggested by several watched titles: strong signal.
            val agreement = 1.0 + 0.35 * (c.because.size - 1)
            val rating = c.item.rating?.let { 0.9 + it / 50.0 } ?: 1.0
            Recommendation(c.item, c.score * agreement * (0.5 + familyShare) * rating, c.because.toList())
        }.sortedByDescending { it.score }.take(limit)
    }

    /** What to show next to [seed]: its suggestions not watched, dismissed, too old or the title itself. */
    fun becauseItems(seed: TasteSeed, taste: TasteData, excluded: Set<String>): List<RecItem> =
        seed.recommendations.filter {
            val key = Taste.key(it.apiName, it.url)
            key !in excluded && key !in taste.dismissed && recentEnough(it.year) && normalizedTitle(it.name) != normalizedTitle(seed.name)
        }.distinctBy { normalizedTitle(it.name) }

    /** "Parce que vous avez regardé X" rows, from the most recent and liked titles. */
    fun becauseRows(taste: TasteData, excluded: Set<String>, maxRows: Int = 3, minItems: Int = 5): List<BecauseRow> =
        taste.seeds.filter { it.weight >= 1.0 && it.recommendations.isNotEmpty() }
            .sortedByDescending { it.lastSeen }
            .mapNotNull { seed ->
                val items = becauseItems(seed, taste, excluded)
                if (items.size >= minItems) BecauseRow(seed, items) else null
            }
            .distinctBy { normalizedTitle(it.seed.name) }
            .take(maxRows)

    /**
     * The single "Parce que vous avez regardé X" row of the home page: X is the last film or series
     * launched ([recentKeys], "apiName|url", most recent first) that has enough suggestions left
     * after [keep]; the ones before it, without suggestions, are skipped.
     */
    fun becauseLastWatched(
        taste: TasteData,
        excluded: Set<String>,
        recentKeys: List<String>,
        minItems: Int = 5,
        keep: (RecItem) -> Boolean = { true },
    ): BecauseRow? {
        val seeds = taste.seeds.associateBy { Taste.key(it.apiName, it.url) }
        return recentKeys.asSequence().distinct().mapNotNull { seeds[it] }
            .filter { family(it.type) == "movie" || family(it.type) == "series" }
            .mapNotNull { seed -> becauseItems(seed, taste, excluded).filter(keep).takeIf { it.size >= minItems }?.let { BecauseRow(seed, it) } }
            .firstOrNull()
    }

    /** Titles not worth recommending: watched, being watched or already in favorites. */
    fun excludedKeys(library: UserLibrary): Set<String> =
        (library.history.map { Taste.key(it.item.apiName, it.item.url) } +
            library.favorites.map { Taste.key(it.apiName, it.url) }).toSet()
}
