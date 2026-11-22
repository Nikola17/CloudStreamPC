package com.moncloudi.providers

import com.moncloudi.core.Recommender
import com.moncloudi.plugins.SearchGate
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Keeps in the Top 10 only the titles the user's addons can play: each candidate is searched in
 * the addons that offer its kind (films or series), under its title in the interface language,
 * then its original title, and the page found must give a video link. What was found is kept to
 * open the title directly in an addon.
 */
object TopAvailability {
    /** A Top 10 title and where it was found (the addon with a source that answered first). */
    data class Entry(val item: TopRecentRanking.Item, val matches: List<SearchResponse>)

    private const val CACHE_TTL_MS = 2 * 3_600_000L
    private const val SEARCH_TIMEOUT_MS = 15_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, SearchResponse?>>()

    fun clearCache() {
        cache.clear()
        sourceCache.clear()
    }

    private val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent)
    private val seriesTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama, TvType.OVA)

    /** Addons that can have this kind of title, with a video source (a Stremio catalog alone has none). */
    fun eligible(providers: List<MainAPI>, type: String): List<MainAPI> = providers.filter { api ->
        val types = if (type == "movie") movieTypes else seriesTypes
        api.supportedTypes.any { it in types } && (api !is StremioProvider || api.canStream(type))
    }

    /** Year of an addon's result, when it gives one. */
    private fun yearOf(item: SearchResponse): Int? = when (item) {
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        is AnimeSearchResponse -> item.year
        else -> null
    }

    /** Title without the year the sites often add ("Dune (2021)", "Dune 2021"). */
    internal fun comparable(name: String): String {
        val normalized = Recommender.normalizedTitle(name)
        // A title that is only a year ("2012") stays as it is.
        return normalized.replace(Regex("\\s*\\b(19|20)\\d{2}\\b\\s*$"), "").trim().ifEmpty { normalized }
    }

    /** Same title (interface language or original), same kind, year within one year when both are known. */
    internal fun sameTitle(result: SearchResponse, item: TopRecentRanking.Item): Boolean {
        val names = listOfNotNull(item.title, item.originalTitle).map(::comparable).filter { it.isNotEmpty() }
        if (comparable(result.name) !in names) return false
        val family = result.type?.let { Recommender.family(it.name) }
        if (family == "live" || (family != null && family != "other" && family != item.type)) return false
        val year = yearOf(result)
        return year == null || item.year == null || abs(year - item.year) <= 1
    }

    /**
     * [item] in [api], or null. Found: remembered two hours; not found: half an hour only (a site
     * may also have refused the search). Searches go through [SearchGate]: in the background they
     * leave the site to the user's searches; [user] (a click on the Top 10) goes first.
     */
    suspend fun searchIn(api: MainAPI, item: TopRecentRanking.Item, user: Boolean = false): SearchResponse? {
        val key = api.name + "|" + item.id
        cache[key]?.takeIf { (now, found) -> System.currentTimeMillis() - now < if (found != null) CACHE_TTL_MS else MISS_TTL_MS }
            ?.let { return it.second }
        val queries = listOfNotNull(item.title, item.originalTitle).distinctBy(::comparable)
        var found: SearchResponse? = null
        var answered = false
        for (query in queries) {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    if (user) withTimeoutOrNull(SEARCH_TIMEOUT_MS) { SearchGate.userSearch(api, query) }
                    else SearchGate.backgroundSearch(api, query)
                }.getOrNull()
            } ?: continue
            answered = true
            found = results.firstOrNull { sameTitle(it, item) }
            if (found != null) break
        }
        // A site that did not answer (or a search dropped for the user's) is asked again next time.
        if (answered) cache[key] = System.currentTimeMillis() to found
        return found
    }

    private const val MISS_TTL_MS = 30 * 60_000L

    private const val LINKS_TIMEOUT_MS = 25_000L
    private const val LINKS_TTL_MS = 6 * 3_600_000L
    private val sourceCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val linksScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * Whether [match] really has a video ("Sources" of its page): its page is loaded, then the
     * links of the film (or first episode) are asked, stopping at the first one. Remembered 6 hours.
     */
    suspend fun hasSource(api: MainAPI, match: SearchResponse): Boolean {
        val key = api.name + "|" + match.url
        sourceCache[key]?.takeIf { System.currentTimeMillis() - it.first < LINKS_TTL_MS }?.let { return it.second }
        // Outside of the caller's scope: an extension blocked in a call that ignores cancellation
        // must not hold the Top 10 past the time limit.
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        val check = linksScope.async {
            val loaded = runCatching { api.load(match.url) }.getOrNull() ?: return@async false
            val episodes = com.moncloudi.player.PlayQueue.episodesByDub(loaded).values.firstOrNull().orEmpty()
            val data = com.moncloudi.player.PlayQueue.build(loaded, episodes).firstOrNull()?.data ?: return@async false
            // Torrent pages play their magnet directly.
            if (data.startsWith(com.moncloudi.player.PlayQueue.TORRENT_DATA)) return@async true
            val first = CompletableDeferred<Unit>()
            val links = linksScope.launch { runCatching { api.loadLinks(data, false, {}) { first.complete(Unit) } } }
            synchronized(jobs) { jobs += links }
            // Done when a link comes, or when the extension has finished without any.
            select<Boolean> {
                first.onAwait { true }
                links.onJoin { first.isCompleted }
            }
        }
        val result = withTimeoutOrNull(LINKS_TIMEOUT_MS) {
            try {
                check.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }
        check.cancel()
        synchronized(jobs) { jobs.forEach { it.cancel() } }
        // A site too slow to answer is asked again next time; a definite answer is kept.
        if (result != null) sourceCache[key] = System.currentTimeMillis() to result
        return result ?: false
    }

    /**
     * The first [wanted] candidates (in ranking order) that one of [providers] can play. Each title
     * is looked for in [preferred] (the addon shown on the home page) first, then in the quickest
     * addons, and the search stops at the first one with a real video source: the sites get as
     * few searches as possible (some refuse them when they come too often, see [SearchGate]).
     * A few titles at a time; [onUpdate] gets the titles kept so far, so the row shows early.
     */
    suspend fun available(
        candidates: List<TopRecentRanking.Item>,
        providers: List<MainAPI>,
        wanted: Int = 10,
        checkSources: Boolean = true,
        preferred: String? = null,
        onUpdate: (List<Entry>) -> Unit = {},
    ): List<Entry> = coroutineScope {
        val type = candidates.firstOrNull()?.type ?: return@coroutineScope emptyList()
        val sources = eligible(providers, type)
        if (sources.isEmpty()) return@coroutineScope emptyList()
        val linkPermits = Semaphore(3)
        val kept = mutableListOf<Entry>()
        for (batch in candidates.chunked(4)) {
            val ordered = sources.sortedWith(compareBy<MainAPI> { it.name != preferred }.thenBy { SearchGate.averageMs(it) })
            kept += batch.map { item ->
                async {
                    for (api in ordered) {
                        val match = searchIn(api, item) ?: continue
                        if (!checkSources || linkPermits.withPermit { hasSource(api, match) }) return@async Entry(item, listOf(match))
                    }
                    null
                }
            }.awaitAll().filterNotNull()
            onUpdate(kept.take(wanted))
            if (kept.size >= wanted) break
        }
        kept.take(wanted)
    }
}
