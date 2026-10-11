package com.cloudstreampc

import com.cloudstreampc.player.Playable
import com.cloudstreampc.player.SkipSegment
import com.cloudstreampc.player.SkipSegments
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AniSkip and AniList answered by a local fake server. */
class SkipSegmentsTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private val api = object : MainAPI() {
        override var name = "Test"
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val uri = exchange.requestURI
            requests += uri.path + "?" + URLDecoder.decode(uri.rawQuery.orEmpty(), Charsets.UTF_8)
            val body = when {
                uri.path == "/v2/skip-times/52991/3" -> """{"found":true,"results":[
                    {"interval":{"startTime":1290.5,"endTime":1380.0},"skipType":"ed","episodeLength":1440},
                    {"interval":{"startTime":64.2,"endTime":153.7},"skipType":"op","episodeLength":1440}]}"""
                uri.path.startsWith("/v2/skip-times/") -> """{"found":false,"results":[]}"""
                uri.path == "/graphql" -> """{"data":{"Page":{"media":[
                    {"idMal":1,"seasonYear":2024,"title":{"romaji":"Sousou no Frieren Movie","english":null},"synonyms":[]},
                    {"idMal":52991,"seasonYear":2023,"title":{"romaji":"Sousou no Frieren","english":"Frieren: Beyond Journey's End"},
                     "synonyms":["Frieren","Frieren at the Funeral"]}]}}}"""
                else -> "{}"
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        SkipSegments.aniSkipBase = base
        SkipSegments.aniListBase = "$base/graphql"
        SkipSegments.clearCache()
        com.cloudstreampc.plugins.PluginManager.configureLibrary()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun readsSegmentsWithTheMalIdOfTheExtension() = runBlocking {
        val response = api.newAnimeLoadResponse("Frieren", "https://site/frieren", TvType.Anime) {}
        with(LoadResponse) { response.addMalId(52991) }
        val segments = SkipSegments.find(response, Playable("ep3", "Épisode 3", "Frieren", 1, 3))
        assertEquals(listOf(SkipSegment("op", 64.2, 153.7), SkipSegment("ed", 1290.5, 1380.0)), segments)
        assertTrue(requests.none { it.startsWith("/graphql") }, "no AniList search when the id is known")
    }

    @Test
    fun findsTheMalIdByTitleWhenTheExtensionHasNone(): Unit = runBlocking {
        val response = api.newAnimeLoadResponse("Frieren [VOSTFR]", "https://site/frieren-vostfr", TvType.Anime) { year = 2023 }
        val segments = SkipSegments.find(response, Playable("ep3", "Épisode 3", "Frieren", null, 3))
        assertEquals("op", segments.first().kind)
        assertTrue(requests.any { it.startsWith("/graphql") })
    }

    @Test
    fun ignoresWhatIsNotAnAnime(): Unit = runBlocking {
        val movie = api.newMovieLoadResponse("Batman", "https://site/batman", TvType.Movie, "data") {}
        assertEquals(emptyList(), SkipSegments.find(movie, Playable("data", null, "Batman")))
        assertTrue(requests.isEmpty())
    }
}
