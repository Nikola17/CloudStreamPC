package com.cloudstreampc

import com.cloudstreampc.providers.Trailers
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Trailers from the extension, or from a fake Cinemeta. */
class TrailersTest {
    private lateinit var server: HttpServer
    private val api = object : MainAPI() {
        override var name = "Test"
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when {
                path.startsWith("/catalog/series/top/search=MobLand") -> """{"metas":[
                    {"id":"tt6046238","name":"Molang","releaseInfo":"2015-"},
                    {"id":"tt31510819","name":"MobLand","releaseInfo":"2025-"}]}"""
                path == "/meta/series/tt31510819.json" -> """{"meta":{"name":"MobLand",
                    "trailers":[{"source":"qKGgw7Ob5f4","type":"Trailer"}],"trailerStreams":[{"ytId":"qKGgw7Ob5f4"}]}}"""
                path.startsWith("/catalog/") -> """{"metas":[]}"""
                else -> "{}"
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        com.cloudstreampc.providers.Cinemeta.base = "http://127.0.0.1:${server.address.port}"
        Trailers.clearCache()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun usesTheExtensionTrailerFirst(): Unit = runBlocking {
        val movie = api.newMovieLoadResponse("Batman", "https://site/batman", TvType.Movie, "data") {}
        with(LoadResponse) { movie.addTrailer("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10") }
        assertEquals("dQw4w9WgXcQ", Trailers.youtubeId(movie))
    }

    @Test
    fun findsTheTrailerOnCinemeta(): Unit = runBlocking {
        val series = api.newTvSeriesLoadResponse("MobLand [VF]", "https://site/mobland", TvType.TvSeries, emptyList()) { year = 2025 }
        assertEquals("qKGgw7Ob5f4", Trailers.youtubeId(series))
        val unknown = api.newMovieLoadResponse("Film inconnu", "https://site/x", TvType.Movie, "data") {}
        assertNull(Trailers.youtubeId(unknown))
    }

    @Test
    fun recognisesYoutubeLinks() {
        assertEquals("qKGgw7Ob5f4", Trailers.youtubeIdOf("https://youtu.be/qKGgw7Ob5f4"))
        assertEquals("qKGgw7Ob5f4", Trailers.youtubeIdOf("https://www.youtube.com/embed/qKGgw7Ob5f4?autoplay=1"))
        assertNull(Trailers.youtubeIdOf("https://vimeo.com/123"))
    }
}
