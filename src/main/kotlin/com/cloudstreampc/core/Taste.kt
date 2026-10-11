package com.cloudstreampc.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import java.io.File

/** A title suggested by a provider (the "Similaires" of a details page). */
data class RecItem(
    val name: String = "",
    val url: String = "",
    val apiName: String = "",
    val posterUrl: String? = null,
    val type: String? = null,
    val year: Int? = null,
    /** Rating out of 10. */
    val rating: Double? = null,
)

/** Something the user watched or liked, with what its provider suggests next to it. */
data class TasteSeed(
    val apiName: String = "",
    val url: String = "",
    val name: String = "",
    val posterUrl: String? = null,
    val type: String? = null,
    val tags: List<String> = emptyList(),
    val recommendations: List<RecItem> = emptyList(),
    /** How much this title says about the user's taste (plays, finished, favorite...). */
    val weight: Double = 0.0,
    val lastSeen: Long = 0,
    /** Last time suggestions were looked up in other providers (when its own had none). */
    val enrichedAt: Long = 0,
    /** Provider the suggestions came from, when it is not [apiName]. */
    val enrichedFrom: String? = null,
)

data class TasteData(
    val seeds: List<TasteSeed> = emptyList(),
    /** "apiName|url" of the titles the user is not interested in. */
    val dismissed: Set<String> = emptySet(),
)

/**
 * What the user watches, kept per profile on this computer only. Used to compute
 * recommendations without any online account (no Simkl / Trakt).
 */
object Taste {
    private const val MAX_SEEDS = 300
    private const val MAX_RECS_PER_SEED = 40

    var data by mutableStateOf(TasteData())
        private set
    private var loadedProfile: String? = null

    private fun file(profile: String) =
        File(AppDirs.root, if (profile == "default") "taste.json" else "taste-$profile.json")

    /** Current profile's data; reloads after a profile switch. */
    fun current(): TasteData {
        val profile = Store.settings.currentProfile
        if (loadedProfile != profile) {
            loadedProfile = profile
            data = readJsonFile(file(profile)) { TasteData() }
        }
        return data
    }

    /** Called after every change (sync folder). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    @Synchronized
    private fun update(block: (TasteData) -> TasteData) {
        current()
        data = block(data)
        runCatching { writeJsonFile(file(loadedProfile ?: "default"), data) }
        runCatching { onChanged?.invoke() }
    }

    /** Data merged from the sync folder (does not notify the sync again). */
    @Synchronized
    fun replace(merged: TasteData) {
        current()
        data = merged
        runCatching { writeJsonFile(file(loadedProfile ?: "default"), data) }
    }

    fun key(apiName: String, url: String) = "$apiName|$url"

    fun SearchResponse.toRecItem() = RecItem(
        name, url, apiName, posterUrl, type?.name,
        when (this) {
            is com.lagradost.cloudstream3.MovieSearchResponse -> year
            is com.lagradost.cloudstream3.TvSeriesSearchResponse -> year
            is com.lagradost.cloudstream3.AnimeSearchResponse -> year
            else -> null
        },
        score?.toDouble(10),
    )

    /** Adds [delta] to the weight of [response], refreshing its suggestions. */
    fun record(response: LoadResponse, delta: Double) {
        if (!Store.settings.recommendationsEnabled) return
        val recs = response.recommendations.orEmpty().take(MAX_RECS_PER_SEED).map { it.toRecItem() }
        update { d ->
            val existing = d.seeds.firstOrNull { it.apiName == response.apiName && it.url == response.url }
            val seed = TasteSeed(
                apiName = response.apiName,
                url = response.url,
                name = response.name,
                posterUrl = response.posterUrl,
                type = response.type.name,
                tags = response.tags.orEmpty().take(10),
                recommendations = recs.ifEmpty { existing?.recommendations.orEmpty() },
                weight = ((existing?.weight ?: 0.0) + delta).coerceIn(0.0, 10.0),
                lastSeen = System.currentTimeMillis(),
            )
            d.copy(seeds = (listOf(seed) + d.seeds.filterNot { it === existing }).take(MAX_SEEDS))
        }
    }

    /** Playback started. */
    fun recordPlay(response: LoadResponse) = record(response, 1.0)

    /** Player closed: finishing counts a lot, giving up in the first minutes lowers the interest. */
    fun recordOutcome(response: LoadResponse, position: Double?, duration: Double?) {
        if (position == null || duration == null || duration <= 0) return
        val fraction = position / duration
        when {
            fraction >= WatchProgress.WATCHED_THRESHOLD -> record(response, 2.0)
            fraction < 0.1 && position < 300 -> record(response, -0.7)
            else -> record(response, 0.5)
        }
    }

    fun recordFavorite(response: LoadResponse, added: Boolean) = record(response, if (added) 1.5 else -1.5)

    /** Stores suggestions found in another provider for a seed that had none. */
    fun setEnrichment(apiName: String, url: String, recs: List<RecItem>, from: String?) = update { d ->
        d.copy(seeds = d.seeds.map {
            if (it.apiName == apiName && it.url == url) {
                it.copy(
                    recommendations = recs.take(MAX_RECS_PER_SEED).ifEmpty { it.recommendations },
                    enrichedAt = System.currentTimeMillis(),
                    enrichedFrom = from ?: it.enrichedFrom,
                )
            } else it
        })
    }

    fun dismiss(apiName: String, url: String) = update { it.copy(dismissed = it.dismissed + key(apiName, url)) }

    fun reset() = update { TasteData() }
}
