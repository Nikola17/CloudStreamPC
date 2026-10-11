package com.cloudstreampc

import com.cloudstreampc.core.IptvAccount
import com.cloudstreampc.core.IptvType
import com.cloudstreampc.providers.IptvProvider
import com.cloudstreampc.providers.XmlTv
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** XMLTV parsing, and the guide of a fake Xtream panel. */
class EpgTest {
    private lateinit var server: HttpServer
    private val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val now = ZonedDateTime.now(ZoneOffset.ofHours(2)).withSecond(0).withNano(0)
    private fun at(minutes: Long) = now.plusMinutes(minutes).format(format)

    private val xmltv by lazy {
        """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv>
          <channel id="France2.fr"><display-name>France 2</display-name></channel>
          <programme start="${at(-30)}" stop="${at(30)}" channel="France2.fr">
            <title lang="fr">Journal de 20h</title><title lang="en">News</title>
            <desc lang="fr">L'actualité &amp; la météo.</desc>
          </programme>
          <programme start="${at(30)}" stop="${at(120)}" channel="France2.fr"><title>Film du soir</title></programme>
          <programme start="${at(-600)}" stop="${at(-500)}" channel="France2.fr"><title>Trop ancien</title></programme>
          <programme start="${at(0)}" stop="${at(60)}" channel="Autre.fr"><title>Autre chaîne</title></programme>
        </tv>"""
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val action = Regex("action=([a-z_]+)").find(query)?.groupValues?.get(1)
            val body = when {
                exchange.requestURI.path.endsWith("/xmltv.php") -> gzip(xmltv)
                action == "get_live_categories" -> """[{"category_id":"1","category_name":"France"}]""".toByteArray()
                action == "get_live_streams" ->
                    """[{"stream_id":5,"name":"France 2","category_id":"1","epg_channel_id":"France2.fr"},
                        {"stream_id":6,"name":"Sans guide","category_id":"1","epg_channel_id":""}]""".toByteArray()
                action == "get_short_epg" -> """{"epg_listings":[]}""".toByteArray()
                action == null -> """{"user_info":{"auth":1}}""".toByteArray()
                else -> "[]".toByteArray()
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun parsesAWindowOfTheGuide() {
        val nowMs = now.toInstant().toEpochMilli()
        val guide = XmlTv.parse(gzip(xmltv).inputStream(), nowMs - 3 * 3600_000L, nowMs + 24 * 3600_000L, setOf("france2.fr"))
        val france2 = guide["france2.fr"]!!
        assertEquals(listOf("Journal de 20h", "Film du soir"), france2.map { it.title })
        assertEquals("L'actualité & la météo.", france2.first().description)
        assertTrue(france2.first().isOn(nowMs))
        assertEquals(nowMs - 30 * 60_000L, france2.first().start)
        assertTrue("autre.fr" !in guide)
    }

    @Test
    fun showsNowAndNextOnTheChannelPage(): Unit = runBlocking {
        val id = "epgtest-${System.nanoTime()}"
        val provider = IptvProvider(IptvAccount(id, "Test", IptvType.XTREAM, "http://127.0.0.1:${server.address.port}", "u", "p"))
        val channels = provider.channels()
        assertEquals(2, channels.size)
        val guide = provider.loadGuide()
        assertEquals(2, guide["france2.fr"]?.size)

        val france2 = channels.first { it.name == "France 2" }
        val page = provider.load(france2.url)
        assertIs<LiveStreamLoadResponse>(page)
        val plot = assertNotNull(page.plot)
        assertTrue(plot.startsWith("En ce moment : Journal de 20h"), plot)
        assertTrue(plot.contains("Ensuite : Film du soir"), plot)
    }
}
