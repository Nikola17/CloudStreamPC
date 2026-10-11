package com.cloudstreampc

import com.cloudstreampc.core.TrackerAccount
import com.cloudstreampc.core.mapper
import com.cloudstreampc.sync.AniListTracker
import com.cloudstreampc.sync.LoginUi
import com.cloudstreampc.sync.MalTracker
import com.cloudstreampc.sync.SimklTracker
import com.cloudstreampc.sync.TraktTracker
import com.cloudstreampc.sync.WatchEvent
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The four tracking services against one fake server playing all of them. */
class TrackersTest {
    private lateinit var server: HttpServer
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var simklPolls = 0
    private var traktPolls = 0
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private val event = WatchEvent("Frieren", 2023, isMovie = false, isAnime = true, season = 1, episode = 3, totalEpisodes = 28, malId = 52991, imdbId = "tt22248376")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            calls += "${exchange.requestMethod} $path ${exchange.requestURI.rawQuery.orEmpty()} $body"
            val (code, answer) = when {
                path == "/anilist" && "Viewer" in body && "name" in body -> 200 to """{"data":{"Viewer":{"name":"nico","id":7}}}"""
                path == "/anilist" && "Viewer" in body -> 200 to """{"data":{"Viewer":{"id":7}}}"""
                path == "/anilist" && "idMal" in body -> 200 to """{"data":{"Media":{"id":154587,"episodes":28,"mediaListEntry":{"progress":2,"status":"CURRENT"}}}}"""
                path == "/anilist" && "SaveMediaListEntry" in body -> 200 to """{"data":{"SaveMediaListEntry":{"id":1}}}"""
                path == "/anilist" && "MediaListCollection" in body -> 200 to """{"data":{"MediaListCollection":{"lists":[{"entries":[
                    {"progress":3,"status":"CURRENT","media":{"title":{"romaji":"Sousou no Frieren","english":"Frieren"},"coverImage":{"large":"http://img/f.jpg"},"episodes":28,"seasonYear":2023}}]}]}}}"""
                path == "/mal/token" -> 200 to """{"access_token":"mal-token","refresh_token":"mal-refresh","expires_in":2592000}"""
                path == "/malapi/users/@me" -> 200 to """{"name":"nico"}"""
                path == "/malapi/anime/52991" -> 200 to """{"num_episodes":28,"my_list_status":{"num_episodes_watched":1}}"""
                path == "/malapi/anime/52991/my_list_status" -> 200 to """{"status":"watching"}"""
                path == "/simkl/oauth/pin" -> 200 to """{"user_code":"ABCD1","verification_url":"https://simkl.com/pin/","expires_in":900,"interval":5}"""
                path == "/simkl/oauth/pin/ABCD1" -> 200 to if (++simklPolls < 2) """{"result":"KO"}""" else """{"result":"OK","access_token":"simkl-token"}"""
                path == "/simkl/users/settings" -> 200 to """{"user":{"name":"nico"}}"""
                path == "/simkl/sync/history" -> 201 to """{"added":{"episodes":1}}"""
                path == "/trakt/oauth/device/code" -> 200 to """{"device_code":"dev","user_code":"XY12","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}"""
                path == "/trakt/oauth/device/token" -> if (++traktPolls < 2) 400 to "" else 200 to """{"access_token":"trakt-token","refresh_token":"r","expires_in":86400,"created_at":${System.currentTimeMillis() / 1000}}"""
                path == "/trakt/users/settings" -> 200 to """{"user":{"username":"nico"}}"""
                path == "/trakt/sync/history" -> 201 to """{"added":{"movies":0,"episodes":1}}"""
                path == "/trakt/sync/watchlist" -> 200 to """[{"type":"movie","movie":{"title":"Dune","year":2021}},{"type":"show","show":{"title":"MobLand","year":2025}}]"""
                else -> 404 to "{}"
            }
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        AniListTracker.apiUrl = "$base/anilist"
        MalTracker.authBase = "$base/mal"
        MalTracker.apiBase = "$base/malapi"
        SimklTracker.apiBase = "$base/simkl"
        SimklTracker.pollDelayOverride = 20
        TraktTracker.apiBase = "$base/trakt"
        TraktTracker.pollDelayOverride = 20
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun call(prefix: String) = calls.filter { it.startsWith(prefix) }

    /** Plays the browser: follows the authorization URL to the app's loopback redirect. */
    private val browser = object : LoginUi {
        val codes = mutableListOf<String>()
        override fun openBrowser(url: String) {
            val query = URI(url).rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }
            Thread {
                Thread.sleep(200)
                val redirect = "${query["redirect_uri"]}?code=the-code&state=${query["state"]}"
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(redirect)).build(), HttpResponse.BodyHandlers.ofString())
            }.start()
        }
        override fun showCode(code: String, url: String) {
            codes += code
        }
    }

    @Test
    fun aniListSendsProgressOnlyForward() = runBlocking {
        val account = TrackerAccount("123", accessToken = "token")
        AniListTracker.markWatched(account, event)
        val save = call("POST /anilist").single { "SaveMediaListEntry" in it }
        val variables = mapper.readTree(save.substringAfter("/anilist  ")).path("variables")
        assertEquals(154587, variables.path("id").asInt())
        assertEquals(3, variables.path("p").asInt())
        assertEquals("CURRENT", variables.path("s").asText())

        calls.clear()
        AniListTracker.markWatched(account, event.copy(episode = 2))
        assertTrue(calls.none { "SaveMediaListEntry" in it }, "episode 2 is behind the AniList progress")

        val list = AniListTracker.list(account)
        assertEquals("Frieren", list.single().title)
        assertEquals("Épisode 3 / 28", list.single().detail)
    }

    @Test
    fun myAnimeListLogsInWithPkceAndUpdatesTheList() = runBlocking {
        val account = MalTracker.login(TrackerAccount("mal-client"), browser)
        assertEquals("mal-token", account.accessToken)
        assertEquals("nico", account.userName)
        val tokenCall = call("POST /mal/token").single()
        assertTrue("code=the-code" in tokenCall && "code_verifier=" in tokenCall && "client_id=mal-client" in tokenCall, tokenCall)

        MalTracker.markWatched(account, event)
        val update = call("PATCH /malapi/anime/52991/my_list_status").single()
        assertTrue("num_watched_episodes=3" in update && "status=watching" in update, update)
    }

    @Test
    fun simklLogsInWithACodeAndSendsHistory() = runBlocking {
        val account = SimklTracker.login(TrackerAccount("simkl-client"), browser)
        assertEquals(listOf("ABCD1"), browser.codes)
        assertEquals("simkl-token", account.accessToken)
        assertEquals("nico", account.userName)

        SimklTracker.markWatched(account, event)
        val body = mapper.readTree(call("POST /simkl/sync/history").single().substringAfter("/simkl/sync/history  "))
        val show = body.path("shows").first()
        assertEquals("tt22248376", show.path("ids").path("imdb").asText())
        assertEquals(52991, show.path("ids").path("mal").asInt())
        assertEquals(3, show.path("seasons").first().path("episodes").first().path("number").asInt())
    }

    @Test
    fun traktUsesTheDeviceFlow() = runBlocking {
        val account = TraktTracker.login(TrackerAccount("trakt-id", "trakt-secret"), browser)
        assertEquals(listOf("XY12"), browser.codes)
        assertEquals("trakt-token", account.accessToken)
        assertTrue(account.expiresAt > System.currentTimeMillis())

        TraktTracker.markWatched(account, event.copy(isAnime = false))
        assertTrue(call("POST /trakt/sync/history").single().contains("\"imdb\":\"tt22248376\""))
        assertEquals(listOf("Dune", "MobLand"), TraktTracker.list(account).map { it.title })
    }
}
