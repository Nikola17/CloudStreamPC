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
data class Playable(
    val data: String,
    val label: String?,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
)

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

    /** What the player is showing; changes when the next episode is loaded in the same window. */
    private class Session(request: PlayRequest, sourceName: String) {
        @Volatile var request = request
        @Volatile var sourceName = sourceName
        @Volatile var position: Double? = null
        @Volatile var duration: Double? = null
        @Volatile var itemStartedAt = System.currentTimeMillis()
        @Volatile var lastSave = 0L
        /** Between "next episode" and the start of its file: position updates are not for the new episode. */
        @Volatile var switching = false
        @Volatile var pendingSubtitles: List<SubtitleFile> = emptyList()

        fun save() {
            val pos = position ?: return
            val dur = duration?.takeIf { it > 0 } ?: return
            val wasWatched = Store.isWatched(request.api.name, request.playable.data)
            Store.saveProgress(request.api.name, request.playable.data, pos, dur)
            // Past 90 %: tell AniList, MyAnimeList, Simkl or Trakt.
            if (!wasWatched && Store.isWatched(request.api.name, request.playable.data)) {
                com.cloudstreampc.sync.Trackers.watched(request.response, request.playable)
            }
        }
    }

    private fun titleOf(playable: Playable) = listOfNotNull(playable.title, playable.label).joinToString(" · ")

    /** Launches the player for [link] and follows the playback until the player is closed. */
    fun play(request: PlayRequest, link: ExtractorLink, subtitles: List<SubtitleFile>) {
        val settings = Store.settings
        val ipcPath = if (settings.player == PlayerKind.MPV && !request.isLive) MpvIpc.newServerPath() else null
        val process = ExternalPlayer.play(
            link, titleOf(request.playable), subtitles, resumePosition(request), ipcPath,
            hasNext = ipcPath != null && request.next != null,
        )
        pending = null
        nextUp = null
        val session = Session(request, link.name)
        started(request, link.name)
        scope.launch { follow(session, process, ipcPath) }
        if (ipcPath != null) scope.launch { sendSegments(ipcPath, request) }
    }

    private fun started(request: PlayRequest, sourceName: String) {
        Store.addHistory(request.savedItem, request.playable.label, request.playable.data)
        runCatching {
            com.cloudstreampc.core.Taste.recordPlay(request.response)
            com.cloudstreampc.providers.TasteEnricher.enrichSoon()
        }
        nowPlaying = NowPlaying(request, sourceName)
        com.cloudstreampc.core.EpisodeWatcher.seen(request.response.apiName, request.response.url)
    }

    /** Gives the player script the parts it can skip (opening, recap, credits). */
    private suspend fun sendSegments(ipcPath: String, request: PlayRequest) {
        if (!Store.settings.playerInterface) return
        val segments = SkipSegments.find(request.response, request.playable)
        if (segments.isEmpty()) return
        Logger.info("Segments à passer : $segments")
        MpvIpc.toScript(ipcPath, "segments", com.cloudstreampc.core.compactJson(segments))
    }

    private fun follow(session: Session, process: Process, ipcPath: String?) {
        if (ipcPath != null) {
            MpvIpc.observe(
                ipcPath, process,
                onEvent = { name, args ->
                    when (name) {
                        "client-message" -> if (args.firstOrNull() == "cspc-next") {
                            scope.launch { playNextInPlayer(session, ipcPath) }
                        }
                        "start-file" -> if (session.switching) {
                            session.switching = false
                            session.position = null
                            session.duration = null
                            session.itemStartedAt = System.currentTimeMillis()
                        }
                        "file-loaded" -> {
                            val subtitles = session.pendingSubtitles
                            session.pendingSubtitles = emptyList()
                            if (subtitles.isNotEmpty()) scope.launch {
                                MpvIpc.send(ipcPath, *subtitles.map { listOf("sub-add", it.url, "auto", it.lang) }.toTypedArray())
                            }
                        }
                    }
                },
            ) { pos, dur ->
                if (session.switching) return@observe
                session.position = pos
                session.duration = dur
                nowPlaying = NowPlaying(session.request, session.sourceName, pos, dur)
                val now = System.currentTimeMillis()
                if (now - session.lastSave > 10_000) {
                    session.lastSave = now
                    session.save()
                }
            }
        }
        val exitCode = process.waitFor()
        if (!session.switching) session.save()
        val request = session.request
        runCatching { com.cloudstreampc.core.Taste.recordOutcome(request.response, session.position, session.duration) }
        if (nowPlaying?.request === request) nowPlaying = null

        // mpv gave up right away (dead link, 403...): offer the other sources again.
        val quickFailure = System.currentTimeMillis() - session.itemStartedAt < 15_000 && session.position == null
        if (ipcPath != null && exitCode != 0 && quickFailure) {
            Logger.info("Échec de lecture de ${session.sourceName} (code $exitCode)")
            pending = request.copy(autoPlay = false, chooseManually = true, failedSources = request.failedSources + session.sourceName)
            return
        }

        val finished = Store.isWatched(request.api.name, request.playable.data)
        Logger.info("Lecteur fermé (position ${session.position?.toInt()} s / ${session.duration?.toInt()} s, terminé : $finished)")
        if (finished && Store.settings.autoPlayNextEpisode) {
            request.next?.let { nextUp = it }
        }
    }

    /** "Épisode suivant" in the player: finds its best source and loads it in the same window. */
    private suspend fun playNextInPlayer(session: Session, ipcPath: String) {
        val current = session.request
        val next = current.next ?: return
        // Moving on from the credits counts as watched (the countdown starts before 90 %).
        val position = session.position
        val duration = session.duration
        if (position != null && duration != null && duration > 0 && position / duration >= 0.7) {
            if (!Store.isWatched(current.api.name, current.playable.data)) {
                Store.setWatched(current.api.name, current.playable.data, true)
                com.cloudstreampc.sync.Trackers.watched(current.response, current.playable)
            }
        } else {
            session.save()
        }
        runCatching { com.cloudstreampc.core.Taste.recordOutcome(current.response, session.position, session.duration) }
        Logger.info("Épisode suivant dans le lecteur : ${next.playable.label}")

        val resolved = LinkResolver.resolve(next)
        val link = bestLink(resolved.links)
        if (link == null) {
            MpvIpc.toScript(ipcPath, "next-failed", "Aucune source trouvée pour l'épisode suivant")
            pending = next.copy(autoPlay = false, chooseManually = true)
            return
        }
        val headers = link.getAllHeaders()
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
            ?: com.lagradost.cloudstream3.USER_AGENT
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: ""
        val otherHeaders = headers.filterKeys { !it.equals("user-agent", true) && !it.equals("referer", true) }
            .map { (k, v) -> "$k: $v" }
        val resume = resumePosition(next)
        Logger.info("Chargement de ${link.name}, reprise à ${resume?.toInt() ?: 0} s")

        session.switching = true
        session.pendingSubtitles = resolved.subtitles
        // Options set before `loadfile` apply to the new file (and replace the previous episode's).
        val sent = MpvIpc.send(
            ipcPath,
            listOf("set_property", "user-agent", userAgent),
            listOf("set_property", "referrer", referer),
            listOf("set_property", "http-header-fields", otherHeaders),
            listOf("set_property", "force-media-title", titleOf(next.playable)),
            listOf("set_property", "start", resume?.let { "+${it.toInt()}" } ?: "none"),
            listOf("loadfile", link.url, "replace"),
            listOf("set_property", "pause", false),
        )
        if (!sent) {
            session.switching = false
            return
        }
        session.request = next
        session.sourceName = link.name
        session.itemStartedAt = System.currentTimeMillis()
        started(next, link.name)
        val info = mapOf("has_next" to (next.next != null), "label" to (next.playable.label ?: next.playable.title))
        MpvIpc.toScript(ipcPath, "episode", com.cloudstreampc.core.compactJson(info))
        sendSegments(ipcPath, next)
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
