package com.cloudstreampc

import com.cloudstreampc.core.IptvAccount
import com.cloudstreampc.core.IptvType
import com.cloudstreampc.providers.IptvProvider
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs the IPTV provider against a fake Xtream panel and a fake M3U playlist. */
class IptvProviderTest {
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private val m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="http://logo/tf1.png" group-title="France",TF1 HD
        http://example.test/live/u/p/1.ts
        #EXTINF:-1 group-title="Films FR",Le Film (2020)
        http://example.test/movie/u/p/10.mkv
        #EXTINF:-1 group-title="Séries FR",Ma Série S01 E01
        http://example.test/series/u/p/100.mp4
        #EXTINF:-1 group-title="Séries FR",Ma Série S01 E02
        http://example.test/series/u/p/101.mp4
    """.trimIndent()

    /** A big provider: 30 000 channels (about 6 MB), copies of BFM TV on other servers. */
    private var bigProvider = false

    private fun bigLiveList(): String = buildString {
        append("[")
        append("""{"stream_id":1,"name":"|FR| BFM TV ᵁᴴᴰ","category_id":"1","epg_channel_id":"bfmtv.fr"},""")
        append("""{"stream_id":2,"name":"|FR| BFM TV ᴴᴰ","category_id":"1","epg_channel_id":"bfmtv.fr"},""")
        append("""{"stream_id":3,"name":"BFM TV |-2H|","category_id":"1","epg_channel_id":""},""")
        for (i in 10 until 30_000) {
            append("""{"num":$i,"stream_id":$i,"name":"|FR| Chaîne numéro $i ᴴᴰ","stream_type":"live","category_id":"1","stream_icon":"http://logo.example.test/very/long/path/$i.png","epg_channel_id":"c$i.fr","added":"1700000000","custom_sid":"","tv_archive":0,"direct_source":"","tv_archive_duration":0},""")
        }
        append("""{"stream_id":4,"name":"France 2","category_id":"1"}]""")
    }

    private fun xtream(action: String?): String = when (action) {
        "get_live_categories" -> """[{"category_id":"1","category_name":"France"}]"""
        "get_live_streams" -> if (bigProvider) bigLiveList() else """[{"stream_id":5,"name":"France 2","category_id":"1","stream_icon":"http://logo/f2.png"}]"""
        "get_vod_categories" -> """[{"category_id":"2","category_name":"Action"}]"""
        "get_vod_streams" -> """[{"stream_id":7,"name":"Un Film","category_id":"2","rating":"7.5","added":"1700000000","container_extension":"mkv"}]"""
        "get_series_categories" -> """[{"category_id":"3","category_name":"Drame"}]"""
        "get_series" -> """[{"series_id":9,"name":"Une Série","category_id":"3","cover":"http://c.png"}]"""
        "get_vod_info" -> """{"info":{"name":"Un Film","plot":"Résumé","releasedate":"2021-05-01","duration_secs":5400,"genre":"Action, Drame"},"movie_data":{"container_extension":"mkv"}}"""
        "get_series_info" -> """{"info":{"name":"Une Série","plot":"Histoire"},"episodes":{"1":[{"id":"55","episode_num":1,"title":"Pilote","container_extension":"mp4","season":1,"info":{}},{"id":"56","episode_num":2,"title":"Suite","container_extension":"mp4","season":1,"info":{}}]}}"""
        "get_short_epg" -> """{"epg_listings":[]}"""
        else -> """{"user_info":{"auth":1}}"""
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val action = Regex("action=([a-z_]+)").find(query)?.groupValues?.get(1)
            val body = (if (exchange.requestURI.path.endsWith(".m3u")) m3u else xtream(action)).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private suspend fun IptvProvider.links(data: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        loadLinks(data, false, {}) { links += it }
        return links
    }

    @Test
    fun xtreamAccount() = runBlocking {
        val provider = IptvProvider(IptvAccount("x", "Test", IptvType.XTREAM, base, "user", "pass"))
        provider.prepare()
        assertTrue(provider.mainPage.any { it.name == "Films · Action" })
        val films = provider.getMainPage(1, MainPageRequest("Films", "recent:MOVIE", false)).items.first().list
        assertEquals("Un Film", films.single().name)

        val movie = assertIs<MovieLoadResponse>(provider.load(films.single().url))
        assertEquals(90, movie.duration)
        assertEquals("$base/movie/user/pass/7.mkv", provider.links(movie.dataUrl).single().url)

        val series = provider.search("série").first { it.name == "Une Série" }
        val loaded = assertIs<TvSeriesLoadResponse>(provider.load(series.url))
        assertEquals(2, loaded.episodes.size)
        assertEquals("$base/series/user/pass/55.mp4", provider.links(loaded.episodes.first().data).single().url)

        val live = assertIs<LiveStreamLoadResponse>(provider.load(provider.search("france 2").single().url))
        assertEquals(2, provider.links(live.dataUrl).size)
    }

    @Test
    fun bigProviderListsAndChannelCopies() = runBlocking {
        bigProvider = true
        assertTrue(bigLiveList().length > 5_000_000, "plus de 5 Mo, comme un gros fournisseur")
        val provider = IptvProvider(IptvAccount("big", "Gros", IptvType.XTREAM, base, "user", "pass"))
        provider.prepare()
        val bfm = provider.search("bfm tv").first { it.name == "|FR| BFM TV ᵁᴴᴰ" }
        // The other quality of the channel is a backup; the time-shifted copy is not.
        assertEquals(listOf("|FR| BFM TV ᴴᴰ"), provider.duplicates(bfm.url).map { it.name })
    }

    @Test
    fun m3uPlaylist() = runBlocking {
        val provider = IptvProvider(IptvAccount("m", "Liste", IptvType.M3U, "$base/list.m3u"))
        provider.prepare()
        val live = assertIs<LiveStreamLoadResponse>(provider.load(provider.search("tf1").single().url))
        assertEquals("http://example.test/live/u/p/1.ts", provider.links(live.dataUrl).single().url)
        val movie = assertIs<MovieLoadResponse>(provider.load(provider.search("film").single().url))
        assertEquals("http://example.test/movie/u/p/10.mkv", provider.links(movie.dataUrl).single().url)
        val series = assertIs<TvSeriesLoadResponse>(provider.load(provider.search("ma série").single().url))
        assertEquals(listOf(1, 2), series.episodes.map { it.episode })
    }
}
