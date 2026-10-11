package com.cloudstreampc.player

import com.cloudstreampc.core.Store
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse

/** Builds the list of things to play from a details page, and picks where to start. */
object PlayQueue {
    /** Episodes grouped by dub status (animes); series have a single `null` group. */
    fun episodesByDub(response: LoadResponse): Map<DubStatus?, List<Episode>> = when (response) {
        is TvSeriesLoadResponse -> mapOf(null to response.episodes)
        is AnimeLoadResponse -> response.episodes.mapKeys { it.key as DubStatus? }.filterValues { it.isNotEmpty() }
        else -> emptyMap()
    }

    fun episodeLabel(ep: Episode, index: Int): String {
        val number = ep.episode ?: (index + 1)
        val prefix = if (ep.season != null) "S${ep.season}·E$number" else "Épisode $number"
        return if (ep.name.isNullOrBlank()) prefix else "$prefix  ${ep.name}"
    }

    fun build(response: LoadResponse, episodes: List<Episode>): List<Playable> = when (response) {
        is MovieLoadResponse -> listOf(Playable(response.dataUrl, null, response.name))
        is LiveStreamLoadResponse -> listOf(Playable(response.dataUrl, null, response.name))
        else -> episodes.mapIndexed { index, ep ->
            Playable(ep.data, episodeLabel(ep, index), response.name, ep.season, ep.episode ?: (index + 1))
        }
    }

    /** Last episode launched, or the next one once it was watched; -1 when nothing is playable. */
    fun startIndex(response: LoadResponse, queue: List<Playable>): Int {
        if (queue.isEmpty()) return -1
        val last = Store.library.history.firstOrNull { it.item.apiName == response.apiName && it.item.url == response.url }
        val lastIndex = queue.indexOfFirst { it.data == last?.episodeData }
        return when {
            lastIndex < 0 -> 0
            Store.isWatched(response.apiName, queue[lastIndex].data) && lastIndex + 1 < queue.size -> lastIndex + 1
            else -> lastIndex
        }
    }

    /** "Lire" from anywhere: resumes where the user left off. */
    fun play(api: MainAPI, response: LoadResponse, autoPlay: Boolean = false): Boolean {
        val queue = build(response, episodesByDub(response).values.firstOrNull().orEmpty())
        val index = startIndex(response, queue)
        if (index < 0) return false
        PlaybackManager.request(PlayRequest(api, response, queue, index, autoPlay = autoPlay))
        return true
    }
}
