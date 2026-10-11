package com.cloudstreampc.core

import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Looks for new episodes of the followed series (favorites, and series watched in the last 60 days)
 * by comparing their episode count with the one seen last time.
 */
object EpisodeWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CHECK_EVERY = 6 * 3600_000L
    private const val RECENT = 60 * 24 * 3600_000L

    /** Called for each new release, to show a desktop notification. */
    @Volatile
    var onNewEpisode: ((NewEpisode) -> Unit)? = null

    fun start() {
        scope.launch {
            delay(30_000) // let the extensions load first
            while (true) {
                if (Store.settings.checkNewEpisodes) runCatching { checkAll() }.onFailure { Logger.error("Nouveaux épisodes", it) }
                delay(CHECK_EVERY)
            }
        }
    }

    fun key(apiName: String, url: String) = "$apiName|$url"

    fun followed(library: UserLibrary = Store.library, now: Long = System.currentTimeMillis()): List<SavedItem> =
        (library.favorites + library.history.filter { now - it.timestamp < RECENT }.map { it.item })
            .filter { Recommender.family(it.type) == "series" }
            .distinctBy { key(it.apiName, it.url) }
            .take(40)

    suspend fun checkAll() = coroutineScope {
        val providers = PluginManager.providers.associateBy { it.name }
        val permits = Semaphore(4)
        val items = followed()
        Logger.info("Nouveaux épisodes : vérification de ${items.size} série(s)")
        items.map { item ->
            async {
                val api = providers[item.apiName] ?: return@async
                permits.withPermit {
                    runCatching {
                        val response = withTimeout(60_000) { api.load(item.url) } ?: return@runCatching
                        record(item, response)
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
            }
        }.awaitAll()
    }

    /** "S2·E5" for the most recent episode. */
    fun latestLabel(episodes: List<Episode>): String? {
        val last = episodes.maxWithOrNull(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 })) ?: return null
        val number = last.episode ?: episodes.size
        return if (last.season != null) "S${last.season}·E$number" else "Épisode $number"
    }

    /** Remembers the episode count of [item]; returns the release when there are more episodes than last time. */
    fun record(item: SavedItem, response: LoadResponse): NewEpisode? {
        val episodes = PlayQueue.episodesByDub(response).values.maxByOrNull { it.size } ?: return null
        return record(item, episodes.size, latestLabel(episodes))
    }

    fun record(item: SavedItem, count: Int, label: String?, now: Long = System.currentTimeMillis()): NewEpisode? {
        if (count <= 0) return null
        val key = key(item.apiName, item.url)
        var found: NewEpisode? = null
        Store.updateLibrary { lib ->
            val previous = lib.episodeSnapshots[key]
            val snapshots = lib.episodeSnapshots + (key to EpisodeSnapshot(count, label, now))
            if (previous == null || count <= previous.count) return@updateLibrary lib.copy(episodeSnapshots = snapshots)
            val existing = lib.newEpisodes.firstOrNull { key(it.item.apiName, it.item.url) == key }
            val release = NewEpisode(item, label ?: "", (existing?.added ?: 0) + count - previous.count, now)
            found = release
            lib.copy(
                episodeSnapshots = snapshots,
                newEpisodes = listOf(release) + lib.newEpisodes.filterNot { key(it.item.apiName, it.item.url) == key },
            )
        }
        found?.let { release ->
            Logger.info("Nouvel épisode : ${item.name} ${release.label}")
            if (Store.settings.newEpisodeNotifications) runCatching { onNewEpisode?.invoke(release) }
        }
        return found
    }

    /** The user opened or played the series: it is no longer "new". */
    fun seen(apiName: String, url: String) {
        val key = key(apiName, url)
        if (Store.library.newEpisodes.none { key(it.item.apiName, it.item.url) == key }) return
        Store.updateLibrary { lib -> lib.copy(newEpisodes = lib.newEpisodes.filterNot { key(it.item.apiName, it.item.url) == key }) }
    }
}
