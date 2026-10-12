package com.cloudstreampc

import com.cloudstreampc.player.Playable
import com.cloudstreampc.player.TorrentEngine
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Torrent links turned into TorrServer streams, against a fake TorrServer. */
class TorrentEngineTest {
    private lateinit var server: HttpServer
    private var statCalls = 0

    private val season = """{"file_stats":[
        {"id":1,"path":"Show.S01/Show.S01E01.1080p.mkv","length":1500000000},
        {"id":2,"path":"Show.S01/Show.S01E02.1080p.mkv","length":1400000000},
        {"id":3,"path":"Show.S01/Show.S01E10.1080p.mkv","length":1450000000},
        {"id":4,"path":"Show.S01/sample.mkv","length":20000000},
        {"id":5,"path":"Show.S01/readme.nfo","length":2000}]}"""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when {
                exchange.requestURI.path == "/echo" -> "MatriX.test"
                exchange.requestURI.rawQuery.orEmpty().contains("stat") -> if (++statCalls < 2) "{}" else season
                else -> "{}"
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        TorrentEngine.port = server.address.port
        TorrentEngine.managed = false
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        TorrentEngine.managed = true
    }

    @Test
    fun picksTheEpisodeInAWholeSeason(): Unit = runBlocking {
        val link = newExtractorLink("Torrentio", "Show S01 (torrent)", "magnet:?xt=urn:btih:abc", ExtractorLinkType.MAGNET)
        val url = TorrentEngine.prepare(link, Playable("x", "S1·E2", "Show", 1, 2))
        assertTrue(url.startsWith("${TorrentEngine.base}/stream/Show?link="), url)
        assertTrue(url.endsWith("&index=2&play"), url)
        assertEquals("magnet:?xt=urn:btih:abc", URLDecoder.decode(url.substringAfter("link=").substringBefore("&index"), Charsets.UTF_8))
        assertTrue(statCalls >= 2, "waits for the torrent metadata")
    }

    @Test
    fun usesTheStremioFileIndex(): Unit = runBlocking {
        val link = newExtractorLink("Torrentio", "Film", "magnet:?xt=urn:btih:def&tr=udp%3A%2F%2Ft&cspc_index=0", ExtractorLinkType.MAGNET)
        val url = TorrentEngine.prepare(link, Playable("x", null, "Film"))
        assertTrue(url.endsWith("&index=1&play"), url)
        assertEquals(0, statCalls, "the index is known: no need to ask for the files")
        assertEquals("magnet:?xt=urn:btih:def&tr=udp%3A%2F%2Ft", URLDecoder.decode(url.substringAfter("link=").substringBefore("&index"), Charsets.UTF_8))
    }

    @Test
    fun picksTheBiggestVideoForAMovie() {
        val files = com.cloudstreampc.core.mapper.readTree(season).path("file_stats").toList()
        assertEquals(1, TorrentEngine.pickFile(files, null, null)?.path("id")?.asInt())
        assertEquals(3, TorrentEngine.pickFile(files, 1, 10)?.path("id")?.asInt())
    }
}
