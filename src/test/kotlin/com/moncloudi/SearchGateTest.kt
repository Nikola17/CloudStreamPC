package com.moncloudi

import com.moncloudi.core.mapper
import com.moncloudi.plugins.SearchGate
import com.moncloudi.providers.StremioAddon
import com.moncloudi.providers.StremioProvider
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Searches sent to a site that refuses them when they come too close (French-Stream), and Stremio
 * addons that answer an empty list to the first search of a title.
 */
class SearchGateTest {
    /** A site that answers nothing when two searches start less than 300 ms apart, like French-Stream. */
    private class FloodSite : MainAPI() {
        override var name = "Site anti-flood"
        override val supportedTypes = setOf(TvType.Movie)
        val starts: MutableList<Long> = Collections.synchronizedList(mutableListOf<Long>())
        val running = AtomicInteger()
        var maxRunning = 0
        var duration = 100L
        override suspend fun search(query: String): List<SearchResponse> {
            val now = System.currentTimeMillis()
            val refused = starts.lastOrNull()?.let { now - it < 300 } ?: false
            starts += now
            maxRunning = maxOf(maxRunning, running.incrementAndGet())
            try {
                delay(duration)
            } finally {
                running.decrementAndGet()
            }
            return if (refused) emptyList() else listOf(newMovieSearchResponse(query, "https://site/$query", TvType.Movie))
        }
    }

    @BeforeTest
    fun fastGate() {
        SearchGate.reset()
        SearchGate.MIN_GAP_MS = 400
        SearchGate.USER_PRIORITY_MS = 1_500
    }

    @AfterTest
    fun normalGate() {
        SearchGate.reset()
        SearchGate.MIN_GAP_MS = 5_000
        SearchGate.USER_PRIORITY_MS = 45_000
        StremioProvider.SEARCH_RETRIES_MS = listOf(0L, 1_500L, 3_000L)
    }

    @Test
    fun searchesToOneSiteAreSentOneAtATimeAndSpaced(): Unit = runBlocking {
        val site = FloodSite()
        // Five searches at once (background ones, like the Top 10 used to do): all answered.
        val results = (1..5).map { i -> async { SearchGate.backgroundSearch(site, "film$i", yieldToUser = false) } }.map { it.await() }
        assertTrue(results.all { it?.size == 1 }, "$results")
        assertEquals(1, site.maxRunning)
        assertTrue(site.starts.zipWithNext().all { (a, b) -> b - a >= 400 }, "${site.starts}")
        // The user's search right after the last one waits its turn instead of being refused.
        assertEquals(1, SearchGate.userSearch(site, "dune").size)
    }

    @Test
    fun theUserSearchGoesFirst(): Unit = runBlocking {
        val site = FloodSite().apply { duration = 1_000 }
        val background = async { SearchGate.backgroundSearch(site, "top10") }
        delay(200)
        // The background search being made is dropped: its answer is unknown (null), not "nothing".
        val started = System.currentTimeMillis()
        assertEquals(1, SearchGate.userSearch(site, "dune").size)
        assertNull(background.await())
        assertTrue(System.currentTimeMillis() - started < 1_800, "the user's search waited for the background one")
        // Then the background ones wait a moment, leaving the site to the user.
        val waitStart = System.currentTimeMillis()
        SearchGate.backgroundSearch(site, "top10")
        assertTrue(System.currentTimeMillis() - waitStart >= 1_000)
    }

    @Test
    fun builtInProvidersAreNotSlowedDown(): Unit = runBlocking {
        val iptv = FloodSite().apply { sourcePlugin = "builtin"; duration = 0 }
        val start = System.currentTimeMillis()
        repeat(3) { SearchGate.userSearch(iptv, "x$it") }
        assertTrue(System.currentTimeMillis() - start < 400)
    }

    @Test
    fun stremioSearchIsAskedAgainWhenTheAddonFirstAnswersNothing(): Unit = runBlocking {
        val calls = AtomicInteger()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.rawPath
            val body = if (calls.incrementAndGet() == 1) """{"metas":[]}"""
            else """{"metas":[{"id":"tt0903747","type":"series","name":"Breaking Bad","releaseInfo":"2008-2013"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            StremioProvider.SEARCH_RETRIES_MS = listOf(0L, 50L, 50L)
            val manifest = mapper.readTree(
                """{"id":"fs","name":"French Stream","resources":["catalog","meta"],"types":["series"],
                "catalogs":[{"type":"series","id":"fs-search","name":"Recherche","extra":[{"name":"search","isRequired":true}]}]}"""
            )
            val provider = StremioProvider(listOf(StremioAddon("http://127.0.0.1:${server.address.port}/manifest.json", manifest)))
            assertEquals(listOf("Breaking Bad"), provider.search("breaking bad").map { it.name })
            // A space is %20 in the path, as Stremio clients send it.
            assertTrue(paths.all { it.endsWith("/search=breaking%20bad.json") }, "$paths")
        } finally {
            server.stop(0)
        }
    }
}
