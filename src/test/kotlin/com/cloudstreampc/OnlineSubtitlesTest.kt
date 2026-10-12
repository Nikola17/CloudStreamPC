package com.cloudstreampc

import com.cloudstreampc.player.OnlineSubtitles
import com.cloudstreampc.player.Playable
import com.cloudstreampc.providers.Cinemeta
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OnlineSubtitlesTest {
    private lateinit var server: HttpServer
    private val api = object : MainAPI() { override var name = "Test" }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when {
                path.startsWith("/catalog/series/top/search=Breaking") -> """{"metas":[{"id":"tt0903747","name":"Breaking Bad","releaseInfo":"2008-2013"}]}"""
                path == "/subtitles/series/tt0903747:2:5.json" -> """{"subtitles":[
                    {"url":"https://subs/1.srt","lang":"por"},{"url":"https://subs/2.srt","lang":"eng"},
                    {"url":"https://subs/3.srt","lang":"fre"},{"url":"https://subs/4.srt","lang":"fre"},{"url":"https://subs/5.srt","lang":"eng"}]}"""
                else -> """{"metas":[],"subtitles":[]}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        Cinemeta.base = "http://127.0.0.1:${server.address.port}"
        Cinemeta.clearCache()
        OnlineSubtitles.base = Cinemeta.base
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun findsTheEpisodeSubtitlesInThePreferredLanguages() = runBlocking {
        assertEquals(listOf("fre", "eng"), OnlineSubtitles.wantedLanguages("fr,fre,fra,french, en"))
        val show = api.newTvSeriesLoadResponse("Breaking Bad", "https://site/bb", TvType.TvSeries, emptyList()) { year = 2008 }
        val found = OnlineSubtitles.find(show, Playable("e", "S2·E5", "Breaking Bad", 2, 5), perLanguage = 1)
            .map { it.lang to it.url }
        val wanted = OnlineSubtitles.wantedLanguages().filter { it == "fre" || it == "eng" }
        assertEquals(wanted.map { lang -> lang to (if (lang == "fre") "https://subs/3.srt" else "https://subs/2.srt") }, found)
    }
}
