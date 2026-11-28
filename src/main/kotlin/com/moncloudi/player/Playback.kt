package com.moncloudi.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moncloudi.core.Logger
import com.moncloudi.core.PlayerKind
import com.moncloudi.core.SavedItem
import com.moncloudi.core.Store
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    /** A live channel (a replay of a past programme plays like a video). */
    val isLive: Boolean get() = response is LiveStreamLoadResponse && !playable.data.startsWith("catchup|")
    val savedItem: SavedItem
        get() = SavedItem(response.name, response.url, response.apiName, response.posterUrl, response.type.name, response.year)
}

data class NowPlaying(
    val request: PlayRequest,
    val sourceName: String,
    val position: Double? = null,
    val duration: Double? = null,
)

/** Keeps track of what the external player is doing (position, end of episode, next episode, live switching). */
object PlaybackManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Request waiting for the user to pick a source (shown by the app as a dialog). */
    var pending by mutableStateOf<PlayRequest?>(null)

    /** What is currently playing in the player. */
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set

    /** Episode proposed after the current one ended. */
    var nextUp by mutableStateOf<PlayRequest?>(null)

    /** What is being prepared before the player opens (torrent), shown by the app. */
    var status by mutableStateOf<String?>(null)

    /** All playable sources found for the current item (for instant VF/VOSTFR/server switching in the player). */
    var availableSources by mutableStateOf<List<ExtractorLink>>(emptyList())
        private set

    /** URL of the source currently loaded in mpv. */
    var currentSourceUrl by mutableStateOf<String?>(null)
        private set

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

    /** What the player is showing; changes when the next episode or another source is loaded in the same window. */
    private class Session(request: PlayRequest, sourceName: String, subtitles: List<SubtitleFile>) {
        @Volatile var request = request
        @Volatile var sourceName = sourceName
        @Volatile var position: Double? = null
        @Volatile var duration: Double? = null
        @Volatile var itemStartedAt = System.currentTimeMillis()
        @Volatile var lastSave = 0L
        /** Between "next episode" / "switch source" and the start of its file: position updates are not for the new item. */
        @Volatile var switching = false
        /** All extension subtitles for the current item (preserved across source switches and auto-fallbacks). */
        @Volatile var subtitles: List<SubtitleFile> = subtitles
        /** Subtitles waiting to be added once mpv emits `file-loaded`. */
        @Volatile var pendingSubtitles: List<SubtitleFile> = subtitles
        /** Live: the provider's link (played directly if the relay cannot handle it) and whether the relay is used. */
        @Volatile var direct: ExtractorLink? = null
        @Volatile var relayed = false
        /** Live: times the stream ended, to stop retrying a channel that is really off. */
        val liveEnds = java.util.concurrent.CopyOnWriteArrayList<Long>()
        /** Seconds really watched of the current item (statistics), and the last position seen. */
        @Volatile var watched = 0.0
        @Volatile var lastPosition: Double? = null

        /** Counts the time watched in the statistics before another item replaces this one. */
        fun flushStats() {
            val seconds = watched
            watched = 0.0
            lastPosition = null
            runCatching { com.moncloudi.core.Stats.record(request.response, request.playable.label?.takeIf { !request.isLive }, seconds) }
        }

        fun save() {
            val pos = position ?: return
            val dur = duration?.takeIf { it > 0 } ?: return
            val wasWatched = Store.isWatched(request.api.name, request.playable.data)
            Store.saveProgress(request.api.name, request.playable.data, pos, dur)
            // Past 90 %: tell AniList, MyAnimeList, Simkl or Trakt.
            if (!wasWatched && Store.isWatched(request.api.name, request.playable.data)) {
                com.moncloudi.sync.Trackers.watched(request.response, request.playable)
            }
        }
    }

    private fun titleOf(playable: Playable) = listOfNotNull(playable.title, playable.label).joinToString(" · ")

    /** The player window showing live TV, reused when changing channel. */
    private class LiveWindow(val session: Session, val process: Process, val ipcPath: String?)

    @Volatile private var liveWindow: LiveWindow? = null
    @Volatile private var activeSession: Session? = null

    /** Launches the player for [link] (after preparing a torrent) and follows the playback until it is closed. */
    fun play(request: PlayRequest, link: ExtractorLink, subtitles: List<SubtitleFile>) {
        if (request.isLive) {
            pending = null
            nextUp = null
            val window = liveWindow?.takeIf { it.process.isAlive && it.ipcPath != null }
            scope.launch {
                runCatching {
                    if (window != null) changeChannel(window.session, window.ipcPath!!, request, link)
                    else launchLive(request, link, subtitles)
                }.onFailure {
                    Logger.error("Direct ${request.response.name}", it)
                    pending = request.copy(autoPlay = false, chooseManually = true, failedSources = request.failedSources + link.name)
                }
            }
            return
        }
        if (!TorrentEngine.isTorrent(link)) return launch(request, link, subtitles)
        pending = null
        status = "Torrent : connexion aux sources…"
        scope.launch {
            try {
                val http = torrentToHttp(link, request.playable)
                status = null
                launch(request, http, subtitles)
            } catch (t: Throwable) {
                status = null
                Logger.error("Torrent ${link.name}", t)
                pending = request.copy(autoPlay = false, chooseManually = true, failedSources = request.failedSources + link.name)
            }
        }
    }

    private suspend fun torrentToHttp(link: ExtractorLink, playable: Playable): ExtractorLink {
        val url = TorrentEngine.prepare(link, playable)
        return com.lagradost.cloudstream3.utils.newExtractorLink(link.source, link.name, url, com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO) {
            quality = link.quality
        }
    }

    /** What the player opens for a live link: the relay's address, or the link itself. */
    private suspend fun liveTarget(request: PlayRequest, link: ExtractorLink): Pair<ExtractorLink, Boolean> {
        if (!LiveTv.relayed(request, link)) return link to false
        val url = LiveRelay.open(LiveTv.sources(request, link))
        return com.lagradost.cloudstream3.utils.newExtractorLink(link.source, link.name, url, com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO) {
            quality = link.quality
        } to true
    }

    private suspend fun launchLive(request: PlayRequest, link: ExtractorLink, subtitles: List<SubtitleFile>) {
        // One connection to the provider at a time: the previous live window goes first.
        liveWindow?.process?.takeIf { it.isAlive }?.destroy()
        LiveRelay.close()
        val (target, relayed) = liveTarget(request, link)
        launch(request, target, subtitles, direct = link, relayed = relayed)
    }

    /** Another channel in the same player window: no new window, no second connection. */
    private suspend fun changeChannel(session: Session, ipcPath: String, request: PlayRequest, link: ExtractorLink) {
        val (target, relayed) = liveTarget(request, link)
        val headers = if (relayed) emptyMap() else link.getAllHeaders()
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: com.lagradost.cloudstream3.USER_AGENT
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: ""
        val otherHeaders = headers.filterKeys { !it.equals("user-agent", true) && !it.equals("referer", true) }
            .map { (k, v) -> "$k: $v" }
        val sent = MpvIpc.send(
            ipcPath,
            *ExternalPlayer.liveBufferCommands().toTypedArray(),
            listOf("set_property", "user-agent", userAgent),
            listOf("set_property", "referrer", referer),
            listOf("set_property", "http-header-fields", otherHeaders),
            listOf("set_property", "force-media-title", titleOf(request.playable)),
            listOf("loadfile", target.url, "replace"),
            listOf("set_property", "pause", false),
        )
        if (!sent) return launchLive(request, link, emptyList())
        session.flushStats()
        session.request = request
        session.sourceName = link.name
        session.direct = link
        session.relayed = relayed
        session.liveEnds.clear()
        session.itemStartedAt = System.currentTimeMillis()
        updateAvailableSources(request, link)
        PlayerSurface.updateTitle(titleOf(request.playable))
        started(request, link.name)
        MpvIpc.toScript(ipcPath, "status", request.response.name)
    }

    /** Next / previous channel or a channel of the list, asked from the player. */
    private suspend fun zap(session: Session, ipcPath: String, target: PlayRequest?) {
        if (target == null) {
            MpvIpc.toScript(ipcPath, "status", "Aucune autre chaîne dans cette liste")
            return
        }
        MpvIpc.toScript(ipcPath, "status", "Chargement : ${target.response.name}…")
        val resolved = LinkResolver.resolve(target)
        val best = bestLink(resolved.links)
        if (best == null) {
            MpvIpc.toScript(ipcPath, "status", "Chaîne indisponible : ${target.response.name}")
            return
        }
        changeChannel(session, ipcPath, target, best)
    }

    /** The live stream ended (the relay gave up, or a direct stream dropped): start it again. */
    private suspend fun liveEnded(session: Session, ipcPath: String) {
        val now = System.currentTimeMillis()
        // The previous channel's stream closing while the next one loads is not an interruption.
        if (now - session.itemStartedAt < 5_000) return
        session.liveEnds.removeIf { now - it > 120_000 }
        session.liveEnds += now
        if (session.liveEnds.size > 5) {
            MpvIpc.toScript(ipcPath, "status", "Chaîne indisponible pour le moment")
            return
        }
        delay(1_500)
        val direct = session.direct ?: return
        val request = session.request
        // Sources the relay cannot read (fMP4, DRM…): let the player read the provider directly.
        val (target, relayed) = if (session.relayed && LiveRelay.lastGiveUpUnreadable) direct to false else liveTarget(request, direct)
        session.relayed = relayed
        val headers = if (relayed) emptyMap() else direct.getAllHeaders()
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: com.lagradost.cloudstream3.USER_AGENT
        MpvIpc.send(ipcPath, listOf("set_property", "user-agent", userAgent), listOf("loadfile", target.url, "replace"), listOf("set_property", "pause", false))
    }

    private fun launch(
        request: PlayRequest,
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        direct: ExtractorLink? = null,
        relayed: Boolean = false,
    ) {
        val settings = Store.settings
        if (settings.player == PlayerKind.MPV && PlayerSurface.enabled) {
            ExternalPlayer.findPlayer(settings.player, settings.playerPath) ?: throw ExternalPlayer.PlayerNotFound(settings.player)
            pending = null
            // The video area must be on screen before mpv can draw into it.
            scope.launch {
                runCatching {
                    val wid = PlayerSurface.acquire(titleOf(request.playable))
                    start(request, link, subtitles, direct, relayed, wid)
                }.onFailure {
                    Logger.error("Lecteur ${link.name}", it)
                    pending = request.copy(autoPlay = false, chooseManually = true, failedSources = request.failedSources + link.name)
                }
            }
            return
        }
        start(request, link, subtitles, direct, relayed, null)
    }

    /** The mpv (or VLC) IPC of what plays now, for the phone remote and embedded controls. */
    @Volatile var currentIpc: String? = null
        private set

    private fun updateAvailableSources(request: PlayRequest, activeLink: ExtractorLink) {
        val found = (listOf(activeLink) + LinkResolver.found(request.playable.data))
            .filter(ExternalPlayer::isPlayable)
            .distinctBy { it.url }
            .sortedWith(compareByDescending<ExtractorLink> { it.url == activeLink.url }.thenByDescending { it.quality }.thenBy { it.name })
        ui {
            availableSources = found
            currentSourceUrl = activeLink.url
        }
    }

    private fun start(
        request: PlayRequest,
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        direct: ExtractorLink?,
        relayed: Boolean,
        wid: Long?,
    ) {
        val settings = Store.settings
        val ipcPath = if (settings.player == PlayerKind.MPV) MpvIpc.newServerPath() else null
        val displayLink = direct ?: link
        updateAvailableSources(request, displayLink)
        val sourcesCount = (listOf(displayLink) + LinkResolver.found(request.playable.data)).distinctBy { it.url }.size
        val process = ExternalPlayer.play(
            link, titleOf(request.playable), subtitles, resumePosition(request), ipcPath,
            hasNext = ipcPath != null && request.next != null,
            hasEpisodes = ipcPath != null && request.queue.size > 1,
            hasSources = ipcPath != null && sourcesCount > 1,
            live = request.isLive,
            wid = wid,
        )
        if (wid != null) {
            PlayerSurface.follow(process, ipcPath)
        } else if (ipcPath != null) {
            // Standalone mpv window: still track audio/sub/speed properties for controller & menu state.
            PlayerController.attach(process, ipcPath)
            scope.launch {
                MpvIpc.watch(ipcPath, process, PlayerController.observedProperties) { name, value ->
                    PlayerController.onProperty(process, name, value)
                }
                PlayerController.detach(process)
            }
        }
        if (ipcPath != null) scope.launch { ExternalPlayer.applySubtitleStyle(ipcPath, process) }
        currentIpc = ipcPath
        pending = null
        nextUp = null
        val session = Session(request, displayLink.name, subtitles)
        session.direct = displayLink
        session.relayed = relayed
        activeSession = session
        started(request, displayLink.name)
        if (request.isLive) {
            liveWindow = LiveWindow(session, process, ipcPath)
            LiveRelay.onStatus = { text ->
                if (liveWindow?.session === session && ipcPath != null) scope.launch { MpvIpc.toScript(ipcPath, "status", text) }
            }
            LiveRelay.onUnsteady = {
                if (liveWindow?.session === session && ipcPath != null) scope.launch { ExternalPlayer.deepenLiveBuffer(ipcPath) }
            }
        }
        scope.launch { follow(session, process, ipcPath) }
        if (ipcPath != null) {
            scope.launch { sendSegments(ipcPath, request) }
            scope.launch { addOnlineSubtitles(session, ipcPath, request) }
        }
    }

    /** Downloads & adds extension subtitles cleanly via IPC (local cached files with proper titles and language codes). */
    private suspend fun addExtensionSubtitles(session: Session, ipcPath: String, subtitles: List<SubtitleFile>) {
        if (subtitles.isEmpty()) return
        val wantedLangs = OnlineSubtitles.wantedLanguages()
        val cached = coroutineScope {
            subtitles.distinctBy { it.url }.map { sub ->
                async(Dispatchers.IO) {
                    val path = OnlineSubtitles.cacheSubtitle(sub.url)
                    val code = OnlineSubtitles.inferLangCode(sub.lang)
                    val title = sub.lang.takeIf { it.isNotBlank() } ?: OnlineSubtitles.displayLanguage(code)
                    Triple(path, title, code)
                }
            }.awaitAll()
        }
        if (activeSession !== session) return
        val alreadyHasSub = PlayerController.activeSubId != null
        var autoUsed = alreadyHasSub
        val commands = cached.map { (path, title, code) ->
            val flag = if (!autoUsed && code in wantedLangs) {
                autoUsed = true
                "auto"
            } else {
                "cached"
            }
            listOf("sub-add", path, flag, title, code)
        }
        if (commands.isNotEmpty()) {
            MpvIpc.send(ipcPath, *commands.toTypedArray())
            Logger.info("${commands.size} sous-titre(s) de l'extension ajoutés")
        }
    }

    /** Adds OpenSubtitles tracks to the player's subtitle menu once the episode plays, cached locally. */
    private suspend fun addOnlineSubtitles(session: Session, ipcPath: String, request: PlayRequest, forceRun: Boolean = false) {
        if ((!Store.settings.onlineSubtitles && !forceRun) || request.isLive) return
        ui { PlayerController.loadingOnlineSubtitles = true }
        try {
            val found = OnlineSubtitles.find(request.response, request.playable, includeEnglishFallback = true)
            if (found.isEmpty()) return
            // Download subtitles in parallel while video buffers so they appear instantaneously on `file-loaded`.
            val cached = coroutineScope {
                found.map { sub ->
                    async(Dispatchers.IO) {
                        val path = OnlineSubtitles.cacheSubtitle(sub.url)
                        Triple(path, sub.title, sub.lang)
                    }
                }.awaitAll()
            }
            // `sub-add` needs the file to be loaded.
            val deadline = System.currentTimeMillis() + 120_000
            while (session.request === request && session.position == null && System.currentTimeMillis() < deadline) delay(400)
            if (session.request !== request || session.position == null) return

            // Never clobber a subtitle the user or extension already selected, and don't force subs on VF audio.
            val existingSub = PlayerController.activeSubId != null ||
                MpvIpc.get(ipcPath, "sid")["sid"]?.takeIf { it.isInt || it.isLong } != null
            val activeAudioLang = PlayerController.audioTracks.firstOrNull { it.id == PlayerController.activeAudioId || it.selected }?.lang
            val audioIsFrench = OnlineSubtitles.inferLangCode(activeAudioLang) == "fre"
            val primaryLang = OnlineSubtitles.wantedLanguages().firstOrNull() ?: "fre"
            var autoSelected = existingSub || audioIsFrench

            val commands = cached.map { (path, title, lang) ->
                val flag = if (!autoSelected && lang == primaryLang) {
                    autoSelected = true
                    "auto"
                } else {
                    "cached"
                }
                listOf("sub-add", path, flag, title, lang)
            }
            if (commands.isNotEmpty()) {
                MpvIpc.send(ipcPath, *commands.toTypedArray())
                Logger.info("${commands.size} sous-titres en ligne ajoutés")
                if (forceRun) MpvIpc.toScript(ipcPath, "status", "${commands.size} sous-titres OpenSubtitles ajoutés")
            }
        } finally {
            ui { PlayerController.loadingOnlineSubtitles = false }
        }
    }

    /** Manually triggers an OpenSubtitles lookup for the currently playing movie/episode. */
    fun reloadOnlineSubtitles() {
        val session = activeSession ?: return
        val ipc = currentIpc ?: return
        scope.launch { addOnlineSubtitles(session, ipc, session.request, forceRun = true) }
    }

    private fun started(request: PlayRequest, sourceName: String) {
        Store.addHistory(request.savedItem, request.playable.label, request.playable.data)
        runCatching {
            com.moncloudi.core.Taste.recordPlay(request.response)
            com.moncloudi.providers.TasteEnricher.enrichSoon()
        }
        nowPlaying = NowPlaying(request, sourceName)
        com.moncloudi.core.EpisodeWatcher.seen(request.response.apiName, request.response.url)
        DiscordPresence.watching(request)
    }

    /** Gives the player script the parts it can skip (opening, recap, credits). */
    private suspend fun sendSegments(ipcPath: String, request: PlayRequest) {
        if (!Store.settings.playerInterface) return
        val segments = SkipSegments.find(request.response, request.playable)
        if (segments.isEmpty()) return
        Logger.info("Segments à passer : $segments")
        MpvIpc.toScript(ipcPath, "segments", com.moncloudi.core.compactJson(segments))
    }

    /** Switches to another resolved source (VF / VOSTFR / server) seamlessly inside the running player at the current position. */
    fun switchSource(link: ExtractorLink) {
        val session = activeSession ?: return
        val ipcPath = currentIpc ?: return
        scope.launch {
            switchSourceInPlayer(session, ipcPath, link)
        }
    }

    private suspend fun switchSourceInPlayer(session: Session, ipcPath: String, link: ExtractorLink) {
        val request = session.request
        if (request.isLive) {
            changeChannel(session, ipcPath, request, link)
            return
        }
        MpvIpc.toScript(ipcPath, "status", "Changement de source : ${link.name}…")
        val resolvedLink = if (TorrentEngine.isTorrent(link)) {
            runCatching { torrentToHttp(link, request.playable) }.onFailure { Logger.error("Torrent ${link.name}", it) }.getOrNull()
        } else {
            link
        }
        if (resolvedLink == null) {
            MpvIpc.toScript(ipcPath, "status", "Source indisponible : ${link.name}")
            return
        }
        val pos = session.position?.takeIf { it > 2.0 }
        val headers = resolvedLink.getAllHeaders()
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
            ?: com.lagradost.cloudstream3.USER_AGENT
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: ""
        val otherHeaders = headers.filterKeys { !it.equals("user-agent", true) && !it.equals("referer", true) }
            .map { (k, v) -> "$k: $v" }

        session.switching = true
        session.pendingSubtitles = session.subtitles
        val sent = MpvIpc.send(
            ipcPath,
            *ExternalPlayer.vodBufferCommands().toTypedArray(),
            listOf("set_property", "user-agent", userAgent),
            listOf("set_property", "referrer", referer),
            listOf("set_property", "http-header-fields", otherHeaders),
            listOf("set_property", "force-media-title", titleOf(request.playable)),
            listOf("set_property", "start", pos?.let { "+${it.toInt()}" } ?: "none"),
            listOf("loadfile", resolvedLink.url, "replace"),
            listOf("set_property", "pause", false),
        )
        if (!sent) {
            session.switching = false
            return
        }
        session.sourceName = link.name
        session.direct = link
        session.itemStartedAt = System.currentTimeMillis()
        updateAvailableSources(request, link)
        nowPlaying = NowPlaying(request, link.name, pos, session.duration)
        rememberSource(request, link.name)
        MpvIpc.toScript(ipcPath, "status", "Source : ${link.name}")
        scope.launch { addOnlineSubtitles(session, ipcPath, request) }
    }

    /** Jumps to any episode index in the current series/anime queue inside the same player window. */
    fun switchEpisode(index: Int) {
        val session = activeSession ?: return
        val ipcPath = currentIpc ?: return
        scope.launch {
            switchEpisodeInPlayer(session, ipcPath, index)
        }
    }

    private fun openSourcesMenu(session: Session, ipcPath: String) {
        val sources = availableSources.ifEmpty {
            LinkResolver.found(session.request.playable.data).filter(ExternalPlayer::isPlayable).distinctBy { it.url }
        }
        if (sources.isEmpty()) {
            MpvIpc.toScript(ipcPath, "status", "Aucune autre source disponible")
            return
        }
        val items = sources.map { link ->
            val q = Qualities.getStringByInt(link.quality).ifBlank { link.type.name }
            val active = link.url == currentSourceUrl || link.name == session.sourceName
            mapOf(
                "title" to link.name,
                "hint" to q,
                "active" to active,
                "value" to listOf("script-message-to", "moncloudi", "switch-source", link.url),
            )
        }
        val selected = sources.indexOfFirst { it.url == currentSourceUrl || it.name == session.sourceName }.coerceAtLeast(0)
        val menu = com.moncloudi.core.compactJson(
            mapOf(
                "type" to "moncloudi-sources",
                "title" to "Sources (VF / VOSTFR / Serveur)",
                "items" to items,
                "selected_index" to selected + 1,
                "search_style" to "on_demand",
            ),
        )
        MpvIpc.send(ipcPath, listOf("script-message-to", "uosc", "open-menu", menu))
    }

    private fun openEpisodesMenu(session: Session, ipcPath: String) {
        val queue = session.request.queue
        if (queue.size < 2) return
        val items = queue.mapIndexed { idx, ep ->
            val watched = Store.isWatched(session.request.api.name, ep.data)
            mapOf(
                "title" to (ep.label ?: ep.title),
                "hint" to if (idx == session.request.index) "En cours" else if (watched) "Vu" else "",
                "active" to (idx == session.request.index),
                "value" to listOf("script-message-to", "moncloudi", "switch-episode", idx.toString()),
            )
        }
        val menu = com.moncloudi.core.compactJson(
            mapOf(
                "type" to "moncloudi-episodes",
                "title" to session.request.response.name,
                "items" to items,
                "selected_index" to session.request.index + 1,
                "search_style" to "on_demand",
            ),
        )
        MpvIpc.send(ipcPath, listOf("script-message-to", "uosc", "open-menu", menu))
    }

    private fun follow(session: Session, process: Process, ipcPath: String?) {
        if (ipcPath != null) {
            MpvIpc.observe(
                ipcPath, process,
                onEvent = { name, args ->
                    when (name) {
                        "client-message" -> when (args.firstOrNull()) {
                            "moncloudi-back" -> PlayerSurface.stop()
                            "moncloudi-next" -> scope.launch { switchEpisodeInPlayer(session, ipcPath, session.request.index + 1) }
                            "moncloudi-sources" -> scope.launch { openSourcesMenu(session, ipcPath) }
                            "moncloudi-switch-source" -> scope.launch {
                                val url = args.getOrNull(1) ?: return@launch
                                val chosen = availableSources.firstOrNull { it.url == url }
                                    ?: LinkResolver.found(session.request.playable.data).firstOrNull { it.url == url }
                                if (chosen != null) switchSourceInPlayer(session, ipcPath, chosen)
                            }
                            "moncloudi-episodes" -> scope.launch { openEpisodesMenu(session, ipcPath) }
                            "moncloudi-switch-episode" -> scope.launch {
                                val idx = args.getOrNull(1)?.toIntOrNull() ?: return@launch
                                switchEpisodeInPlayer(session, ipcPath, idx)
                            }
                            "moncloudi-live-eof" -> scope.launch { liveEnded(session, ipcPath) }
                            "moncloudi-zap" -> scope.launch {
                                zap(session, ipcPath, LiveTv.neighbour(session.request, args.getOrNull(1)?.toIntOrNull() ?: 1))
                            }
                            "moncloudi-zap-to" -> scope.launch {
                                val url = args.getOrNull(1)
                                if (url != null) zap(session, ipcPath, LiveTv.requestFor(session.request.api, url))
                            }
                            "moncloudi-channels" -> scope.launch {
                                LiveTv.channelMenu(session.request)?.let { menu ->
                                    MpvIpc.send(ipcPath, listOf("script-message-to", "uosc", "open-menu", menu))
                                } ?: MpvIpc.toScript(ipcPath, "status", "Liste des chaînes indisponible")
                            }
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
                                addExtensionSubtitles(session, ipcPath, subtitles)
                            }
                        }
                    }
                },
            ) { pos, dur ->
                if (session.switching) return@observe
                // Normal playback moves forward by small steps; seeks and pauses are not watching time.
                val previous = session.lastPosition
                if (pos != null && previous != null && pos - previous in 0.0..3.0) session.watched += pos - previous
                session.lastPosition = pos
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
        if (currentIpc == ipcPath) currentIpc = null
        if (activeSession === session) {
            activeSession = null
            ui { availableSources = emptyList(); currentSourceUrl = null }
        }
        if (liveWindow?.session === session) {
            liveWindow = null
            LiveRelay.close()
        }
        if (!session.switching) session.save()
        session.flushStats()
        val request = session.request
        runCatching { com.moncloudi.core.Taste.recordOutcome(request.response, session.position, session.duration) }
        if (nowPlaying?.request === request) nowPlaying = null
        DiscordPresence.clear()

        // mpv gave up right away (dead link, 403...): in auto-play mode try the next best source automatically
        // (up to 2 automatic fallbacks) before falling back to the manual source picker.
        val quickFailure = System.currentTimeMillis() - session.itemStartedAt < 15_000 && session.position == null
        if (ipcPath != null && exitCode != 0 && quickFailure) {
            Logger.info("Échec de lecture de ${session.sourceName} (code $exitCode)")
            val updatedFailed = request.failedSources + session.sourceName
            val autoRetry = !request.chooseManually && (request.autoPlay || Store.settings.autoPlayBestSource) && updatedFailed.size <= 2
            val fallback = if (autoRetry) bestLink(LinkResolver.found(request.playable.data), updatedFailed) else null
            if (fallback != null) {
                Logger.info("Bascule automatique vers la source suivante : ${fallback.name}")
                play(request.copy(failedSources = updatedFailed), fallback, session.subtitles)
                return
            }
            pending = request.copy(autoPlay = false, chooseManually = true, failedSources = updatedFailed)
            return
        }

        // It played for a while: try this source first next time for this title.
        if ((session.position ?: 0.0) > 60) rememberSource(request, session.sourceName)
        val finished = Store.isWatched(request.api.name, request.playable.data)
        Logger.info("Lecteur fermé (position ${session.position?.toInt()} s / ${session.duration?.toInt()} s, terminé : $finished)")
        if (finished && Store.settings.autoPlayNextEpisode) {
            request.next?.let { nextUp = it }
        }
    }

    /** Loads any episode [targetIndex] in the same player window (used by "Épisode suivant" and the episode picker). */
    private suspend fun switchEpisodeInPlayer(session: Session, ipcPath: String, targetIndex: Int) {
        val current = session.request
        if (targetIndex !in current.queue.indices || targetIndex == current.index) return
        val next = current.copy(index = targetIndex, autoPlay = true, failedSources = emptyList())
        // Moving on from the credits counts as watched (the countdown starts before 90 %).
        val position = session.position
        val duration = session.duration
        if (targetIndex == current.index + 1 && position != null && duration != null && duration > 0 && position / duration >= 0.7) {
            if (!Store.isWatched(current.api.name, current.playable.data)) {
                Store.setWatched(current.api.name, current.playable.data, true)
                com.moncloudi.sync.Trackers.watched(current.response, current.playable)
            }
        } else {
            session.save()
        }
        runCatching { com.moncloudi.core.Taste.recordOutcome(current.response, session.position, session.duration) }
        Logger.info("Changement d'épisode dans le lecteur : ${next.playable.label}")
        MpvIpc.toScript(ipcPath, "status", "Chargement : ${next.playable.label ?: next.playable.title}…")

        val resolved = LinkResolver.resolve(next)
        val best = bestLink(resolved.links, remembered = rememberedSource(next))
        val link = if (best != null && TorrentEngine.isTorrent(best)) {
            runCatching { torrentToHttp(best, next.playable) }.onFailure { Logger.error("Torrent ${best.name}", it) }.getOrNull()
        } else {
            best
        }
        if (link == null || best == null) {
            MpvIpc.toScript(ipcPath, "next-failed", "Aucune source trouvée pour cet épisode")
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
        Logger.info("Chargement de ${best.name}, reprise à ${resume?.toInt() ?: 0} s")

        session.switching = true
        session.subtitles = resolved.subtitles
        session.pendingSubtitles = resolved.subtitles
        // Options set before `loadfile` apply to the new file (keep VOD buffer settings, never live TV's 8s/60s!).
        val sent = MpvIpc.send(
            ipcPath,
            *ExternalPlayer.vodBufferCommands().toTypedArray(),
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
        session.flushStats()
        session.request = next
        session.sourceName = best.name
        session.direct = best
        session.itemStartedAt = System.currentTimeMillis()
        updateAvailableSources(next, best)
        PlayerSurface.updateTitle(titleOf(next.playable))
        started(next, best.name)
        val info = mapOf("has_next" to (next.next != null), "label" to (next.playable.label ?: next.playable.title))
        MpvIpc.toScript(ipcPath, "episode", com.moncloudi.core.compactJson(info))
        scope.launch { addOnlineSubtitles(session, ipcPath, next) }
        sendSegments(ipcPath, next)
    }

    /** Picks the source to play automatically, respecting the preferred quality. */
    fun bestLink(
        links: List<ExtractorLink>,
        failedSources: List<String> = emptyList(),
        /** Source that worked last time for this title: played first when it is there. */
        remembered: String? = null,
    ): ExtractorLink? {
        val all = links.filter { ExternalPlayer.isPlayable(it) && it.name !in failedSources }
        // Torrents start slower: only when there is nothing else.
        val playable = all.filterNot(TorrentEngine::isTorrent).ifEmpty { all }
        if (playable.isEmpty()) return null
        // Unknown quality is usually an adaptive (multi-quality) HLS playlist: rank it just under 720p.
        fun rank(link: ExtractorLink) =
            if (link.quality == Qualities.Unknown.value || link.quality <= 0) 719 else link.quality
        val limit = Store.settings.preferredQuality
        fun pick(candidates: List<ExtractorLink>): ExtractorLink? {
            val withinLimit = candidates.filter { limit == null || rank(it) <= limit }
            return withinLimit.maxByOrNull(::rank) ?: candidates.minByOrNull(::rank)
        }
        remembered?.let { name -> pick(playable.filter { it.name == name })?.let { return it } }
        return pick(playable)
    }

    fun rememberedSource(request: PlayRequest): String? =
        Store.library.preferredSources["${request.response.apiName}|${request.response.url}"]

    private fun rememberSource(request: PlayRequest, sourceName: String) {
        val key = "${request.response.apiName}|${request.response.url}"
        if (Store.library.preferredSources[key] == sourceName) return
        Store.updateLibrary { lib -> lib.copy(preferredSources = (lib.preferredSources + (key to sourceName)).entries.toList().takeLast(500).associate { it.key to it.value }) }
    }

    private fun ui(block: () -> Unit) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) block() else javax.swing.SwingUtilities.invokeLater(block)
    }
}
