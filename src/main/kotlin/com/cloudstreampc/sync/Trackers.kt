package com.cloudstreampc.sync

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.TrackerAccount
import com.cloudstreampc.player.Playable
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.player.SkipSegments
import com.cloudstreampc.providers.Cinemeta
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What was just watched, in the terms the tracking services use. */
data class WatchEvent(
    val title: String,
    val year: Int?,
    val isMovie: Boolean,
    val isAnime: Boolean,
    val season: Int?,
    val episode: Int?,
    /** Episodes of the season (or of the anime), to mark it completed after the last one. */
    val totalEpisodes: Int?,
    val malId: Int?,
    val imdbId: String?,
)

/** A title of the user's list on a tracking service. */
data class TrackedTitle(val title: String, val posterUrl: String?, val detail: String?, val year: Int? = null)

/** How the login dialog shows the steps of a login. */
interface LoginUi {
    fun openBrowser(url: String)

    /** Device-code logins: the code to type on the service's page. */
    fun showCode(code: String, url: String)
}

interface Tracker {
    val id: String
    val name: String
    /** Page where the user creates their API client. */
    val developerUrl: String
    /** What to fill in on that page, shown in the login dialog. */
    val setupSteps: List<String>
    val needsSecret: Boolean
    val animeOnly: Boolean

    /** Logs in and returns the account with its tokens and user name. */
    suspend fun login(account: TrackerAccount, ui: LoginUi): TrackerAccount

    /** Sends the progress; returns the account when its tokens were refreshed. */
    suspend fun markWatched(account: TrackerAccount, event: WatchEvent): TrackerAccount?

    /** Titles the user is watching (or wants to watch) on the service. */
    suspend fun list(account: TrackerAccount): List<TrackedTitle>

    /** Puts the title in the service's matching list; returns the account when its tokens were refreshed. */
    suspend fun setStatus(account: TrackerAccount, event: WatchEvent, status: com.cloudstreampc.core.WatchStatus): TrackerAccount? = null
}

/** The tracking accounts: AniList and MyAnimeList (animes), Simkl and Trakt (everything). */
object Trackers {
    val all: List<Tracker> = listOf(AniListTracker, MalTracker, SimklTracker, TraktTracker)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun account(tracker: Tracker): TrackerAccount? = Store.settings.trackers[tracker.id]

    fun isConnected(tracker: Tracker) = account(tracker)?.accessToken != null

    fun connected() = all.filter(::isConnected)

    fun save(tracker: Tracker, account: TrackerAccount?) = Store.updateSettings { s ->
        s.copy(trackers = if (account == null) s.trackers - tracker.id else s.trackers + (tracker.id to account))
    }

    fun disconnect(tracker: Tracker) {
        val account = account(tracker) ?: return
        save(tracker, account.copy(accessToken = null, refreshToken = null, expiresAt = 0, userName = null))
    }

    /** An episode or movie was watched to the end: tell the connected services, in the background. */
    fun watched(response: LoadResponse, playable: Playable) {
        val connected = connected()
        if (connected.isEmpty()) return
        scope.launch {
            val event = runCatching { eventFor(response, playable) }.getOrElse {
                Logger.error("Suivi : ${response.name}", it)
                return@launch
            }
            connected.filter { !it.animeOnly || event.isAnime }.forEach { tracker ->
                val account = account(tracker) ?: return@forEach
                try {
                    tracker.markWatched(account, event)?.let { save(tracker, it) }
                    Logger.info("${tracker.name} : ${event.title} ${event.episode?.let { "épisode $it" } ?: ""} marqué comme vu")
                } catch (t: Throwable) {
                    Logger.error("${tracker.name} : ${event.title}", t)
                }
            }
        }
    }

    /** A title was put in a library list: same list on the connected services, in the background. */
    fun statusChanged(response: LoadResponse, status: com.cloudstreampc.core.WatchStatus) {
        val connected = connected()
        if (connected.isEmpty()) return
        scope.launch {
            val event = runCatching { eventFor(response, Playable(response.url, null, response.name)) }.getOrElse {
                Logger.error("Suivi : ${response.name}", it)
                return@launch
            }
            connected.filter { !it.animeOnly || event.isAnime }.forEach { tracker ->
                val account = account(tracker) ?: return@forEach
                runCatching { tracker.setStatus(account, event, status)?.let { save(tracker, it) } }
                    .onSuccess { Logger.info("${tracker.name} : ${event.title} → ${status.label}") }
                    .onFailure { Logger.error("${tracker.name} : ${event.title}", it) }
            }
        }
    }

    suspend fun eventFor(response: LoadResponse, playable: Playable): WatchEvent {
        val isAnime = SkipSegments.isAnime(response)
        val isMovie = response.type in setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)
        val episodes = PlayQueue.episodesByDub(response).values.maxByOrNull { it.size }.orEmpty()
        val total = episodes.count { it.season == playable.season }.takeIf { it > 0 }
        val malId = if (isAnime) runCatching { SkipSegments.malId(response, playable.season) }.getOrNull() else null
        val imdbId = runCatching { Cinemeta.imdbId(response) }.getOrNull()
        return WatchEvent(
            Cinemeta.cleanTitle(response.name), response.year, isMovie, isAnime,
            playable.season, if (isMovie) null else playable.episode, total, malId, imdbId,
        )
    }

    /** Tokens close to expiry are refreshed before use. */
    fun expiresSoon(account: TrackerAccount) = account.expiresAt in 1..(System.currentTimeMillis() + 3600_000L)
}
