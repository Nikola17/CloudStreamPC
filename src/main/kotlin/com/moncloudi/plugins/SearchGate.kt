package com.moncloudi.plugins

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Every search the app sends to an extension goes through here.
 *
 * Some sites refuse a search that comes too soon after the previous one from the same address
 * (French-Stream: « Tu cherches trop vite ! … attends quelques secondes »), and answer a page
 * without any result, which the extension turns into "nothing found". The searches the app makes
 * by itself (Top 10, other addons of a details page, recommendations, health check) used to fall
 * just before the user's own search, which then found nothing.
 *
 * So, for each extension: one search at a time, at least [MIN_GAP_MS] between the starts of two
 * searches, and the searches the user types first: the background ones wait [USER_PRIORITY_MS]
 * after the last one, and the background search being made is dropped to free the site.
 * Built-in providers (IPTV, searched on the computer, and Stremio) are not concerned.
 */
object SearchGate {
    /** Between the starts of two searches on one extension. */
    internal var MIN_GAP_MS = 5_000L
    /** Background searches wait this long after the user's last search. */
    internal var USER_PRIORITY_MS = 45_000L

    private class Lane {
        val mutex = Mutex()
        @Volatile var lastStart = 0L
        @Volatile var background: Deferred<*>? = null
        /** Average duration of a search, to try the quick addons first. */
        @Volatile var averageMs = 0.0
        @Volatile var samples = 0
    }

    private val lanes = ConcurrentHashMap<String, Lane>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastUserSearch = 0L

    /** Test hook: the clock. */
    internal var now: () -> Long = System::currentTimeMillis

    private fun gated(api: MainAPI) = api.sourcePlugin != "builtin"

    /** Usual duration of a search in [api] (3 s when not known yet). */
    fun averageMs(api: MainAPI): Double = lanes[api.name]?.takeIf { it.samples > 0 }?.averageMs ?: 3_000.0

    /** A search the user asked for: sent as soon as the site allows it. */
    suspend fun userSearch(api: MainAPI, query: String): List<SearchResponse> {
        if (!gated(api)) return api.search(query, 1)?.items.orEmpty()
        lastUserSearch = now()
        val lane = lanes.getOrPut(api.name) { Lane() }
        lane.background?.cancel()
        return lane.mutex.withLock { send(lane, api, query) }
    }

    /**
     * A search the app makes by itself. Null when it was dropped for a search of the user (the
     * answer is unknown, not "nothing found"). [yieldToUser]: waits until the user has not searched
     * for [USER_PRIORITY_MS] (Top 10, recommendations); false for what the user is looking at
     * (other addons of the details page), which only keeps its turn and the gap.
     */
    suspend fun backgroundSearch(api: MainAPI, query: String, yieldToUser: Boolean = true): List<SearchResponse>? {
        if (!gated(api)) return api.search(query, 1)?.items.orEmpty()
        val lane = lanes.getOrPut(api.name) { Lane() }
        fun userFirst() = yieldToUser && lastUserSearch + USER_PRIORITY_MS > now()
        while (true) {
            if (userFirst()) {
                delay((lastUserSearch + USER_PRIORITY_MS - now()).coerceIn(1, 2_000))
                continue
            }
            val outcome = lane.mutex.withLock {
                // The user searched while this one was waiting for its turn: wait again.
                if (userFirst()) return@withLock Outcome.Later
                // Run apart, so that a search of the user can drop it without cancelling the caller.
                val job = scope.async { send(lane, api, query) }
                lane.background = job
                try {
                    Outcome.Done(job.await())
                } catch (e: CancellationException) {
                    if (job.isCancelled && currentCoroutineContext().isActive) Outcome.Done(null) else throw e
                } finally {
                    if (lane.background === job) lane.background = null
                }
            }
            if (outcome is Outcome.Done) return outcome.items
        }
    }

    private sealed interface Outcome {
        data object Later : Outcome
        data class Done(val items: List<SearchResponse>?) : Outcome
    }

    private suspend fun send(lane: Lane, api: MainAPI, query: String): List<SearchResponse> {
        val wait = lane.lastStart + MIN_GAP_MS - now()
        if (wait > 0) delay(wait)
        val start = now()
        lane.lastStart = start
        val items = api.search(query, 1)?.items.orEmpty()
        val took = (now() - start).toDouble()
        lane.averageMs = if (lane.samples == 0) took else lane.averageMs * 0.7 + took * 0.3
        lane.samples++
        return items
    }

    internal fun reset() {
        lanes.clear()
        lastUserSearch = 0
    }
}
