package com.cloudstreampc.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.Store
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Something that can be played: a movie, an episode or a live channel. */
data class Playable(val data: String, val label: String?, val title: String)

/** What to play, with the episodes that follow it for "next episode". */
data class PlayRequest(
    val api: MainAPI,
    val response: LoadResponse,
    val queue: List<Playable>,
    val index: Int,
    /** Play the best source without showing the list. */
    val autoPlay: Boolean = false,
    /** Names of the sources that failed to play for this request. */
    val failedSources: List<String> = emptyList(),
    /** Show the source list even when the best source plays automatically. */
    val chooseManually: Boolean = false,
) {
    val playable: Playable get() = queue[index]
    val next: PlayRequest?
        get() = if (index + 1 < queue.size) copy(index = index + 1, autoPlay = true, failedSources = emptyList()) else null
    val isLive: Boolean get() = response is LiveStreamLoadResponse
    val savedItem: SavedItem
        get() = SavedItem(response.name, response.url, response.apiName, response.posterUrl, response.type.name, response.year)
}

data class NowPlaying(
    val request: PlayRequest,
    val sourceName: String,
    val position: Double? = null,
    val duration: Double? = null,
)

/** Keeps track of what the external player is doing (position, end of episode, next episode). */
object PlaybackManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Request waiting for the user to pick a source (shown by the app as a dialog). */
    var pending by mutableStateOf<PlayRequest?>(null)

    /** What is currently playing in the external player. */
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set

    /** Episode proposed after the current one ended. */
    var nextUp by mutableStateOf<PlayRequest?>(null)

    fun request(request: PlayRequest) {
        nextUp = null
        pending = request
    }

    /** Position to resume from, if the movie/episode was left unfinished. */
    fun resumePosition(request: PlayRequest): Double? {
        if (request.isLive || !Store.settings.resumePlayback) return null
        val progress = Store.progressOf(request.api.name, request.playable.data) ?: return null
        return progress.position.takeIf { it > 15 && !progress.finished }?.minus(5)
    }

    /** Launches the player for [link] and follows the playback until the player is closed. */
    fun play(request: PlayRequest, link: ExtractorLink, subtitles: List<SubtitleFile>) {
        val playable = request.playable
        val title = listOfNotNull(playable.title, playable.label).joinToString(" · ")
        val settings = Store.settings
        val ipcPath = if (settings.player == PlayerKind.MPV && !request.isLive) MpvIpc.newServerPath() else null
        val process = ExternalPlayer.play(link, title, subtitles, resumePosition(request), ipcPath)

        pending = null
        nextUp = null
        Store.addHistory(request.savedItem, playable.label, playable.data)
        val session = NowPlaying(request, link.name)
        nowPlaying = session

        val startedAt = System.currentTimeMillis()
        scope.launch {
            var position: Double? = null
            var duration: Double? = null
            var lastSave = 0L
            fun save() {
                val pos = position ?: return
                val dur = duration?.takeIf { it > 0 } ?: return
                Store.saveProgress(request.api.name, playable.data, pos, dur)
            }

            if (ipcPath != null) {
                MpvIpc.observe(ipcPath, process) { pos, dur ->
                    position = pos
                    duration = dur
                    nowPlaying = session.copy(position = pos, duration = dur)
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 10_000) {
                        lastSave = now
                        save()
                    }
                }
            }
            val exitCode = process.waitFor()
            save()
            if (nowPlaying?.request === request) nowPlaying = null

            // mpv gave up right away (dead link, 403...): offer the other sources again.
            val quickFailure = System.currentTimeMillis() - startedAt < 15_000 && position == null
            if (ipcPath != null && exitCode != 0 && quickFailure) {
                Logger.info("Échec de lecture de ${link.name} (code $exitCode)")
                pending = request.copy(autoPlay = false, chooseManually = true, failedSources = request.failedSources + link.name)
                return@launch
            }

            val finished = Store.isWatched(request.api.name, playable.data)
            Logger.info("Lecteur fermé (position ${position?.toInt()} s / ${duration?.toInt()} s, terminé : $finished)")
            if (finished && Store.settings.autoPlayNextEpisode) {
                request.next?.let { nextUp = it }
            }
        }
    }

    /** Picks the source to play automatically, respecting the preferred quality. */
    fun bestLink(links: List<ExtractorLink>, failedSources: List<String> = emptyList()): ExtractorLink? {
        val playable = links.filter { ExternalPlayer.isPlayable(it) && it.name !in failedSources }
        if (playable.isEmpty()) return null
        // Unknown quality is usually an adaptive (multi-quality) HLS playlist: rank it just under 720p.
        fun rank(link: ExtractorLink) =
            if (link.quality == Qualities.Unknown.value || link.quality <= 0) 719 else link.quality
        val limit = Store.settings.preferredQuality
        val withinLimit = playable.filter { limit == null || rank(it) <= limit }
        return withinLimit.maxByOrNull(::rank) ?: playable.minByOrNull(::rank)
    }
}
