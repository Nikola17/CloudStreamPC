package com.moncloudi

import com.moncloudi.core.RecItem
import com.moncloudi.core.Recommender
import com.moncloudi.core.TasteData
import com.moncloudi.core.TasteSeed
import com.moncloudi.plugins.RepositoryManager
import com.moncloudi.providers.TopAvailability
import com.moncloudi.providers.TopRecentRanking
import com.moncloudi.providers.Trailers
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home page fixes: recommendations, Top 10 availability, dubbed trailers, repository short codes. */
class HomeFixesTest {
    private val now = 1_800_000_000_000L

    private fun rec(name: String, year: Int?) = RecItem(name, "https://FS/${name.replace(' ', '-')}", "FS", null, "Movie", year, 7.0)
    private fun seed(name: String, recs: List<RecItem>, daysAgo: Long = 0) =
        TasteSeed("FS", "https://FS/$name", name, null, "Movie", emptyList(), recs, 2.0, now - daysAgo * 86_400_000L)

    @Test
    fun nothingBefore1990IsRecommended() {
        val taste = TasteData(listOf(seed("Heat", listOf(rec("Le Parrain", 1972), rec("Collateral", 2004), rec("Sans année", null)))))
        val names = Recommender.recommend(taste, emptySet(), now).map { it.item.name }
        assertFalse("Le Parrain" in names)
        assertTrue("Collateral" in names && "Sans année" in names, "$names")
        assertTrue(Recommender.recentEnough(1990) && !Recommender.recentEnough(1989))
    }

    @Test
    fun becauseRowFollowsTheLastLaunchedTitle() {
        val many = { prefix: String -> (1..6).map { rec("$prefix $it", 2015) } }
        // "Heat" was seen more recently in the taste data, but "Dune" is the last one launched.
        val taste = TasteData(listOf(seed("Heat", many("Polar")), seed("Dune", many("SF"), daysAgo = 3)))
        val row = Recommender.becauseLastWatched(taste, emptySet(), listOf("FS|https://FS/Dune", "FS|https://FS/Heat"))
        assertEquals("Dune", row?.seed?.name)
        // The last one without enough suggestions is skipped.
        val thin = TasteData(listOf(seed("Heat", many("Polar")), seed("Dune", listOf(rec("SF", 2015)))))
        assertEquals("Heat", Recommender.becauseLastWatched(thin, emptySet(), listOf("FS|https://FS/Dune", "FS|https://FS/Heat"))?.seed?.name)
    }

    @Test
    fun filmsStillInCinemasAreLeftOutOfTheTop10() {
        val today = LocalDate.of(2026, 11, 22)
        fun movie(id: String, released: LocalDate) =
            TopRecentRanking.Item(id, id, null, released.year, 7.8, released, emptyList(), 200.0, 0.0, "movie")
        val ranked = TopRecentRanking.rankCandidates(
            listOf(movie("en_salle", today.minusDays(20)), movie("sorti", today.minusDays(150))), today,
        ).map { it.id }
        assertEquals(listOf("sorti"), ranked)
        // Series have no cinema release.
        val series = TopRecentRanking.Item("serie", "Série", null, 2026, 8.0, today.minusDays(20), emptyList(), 200.0, 0.0, "series")
        assertEquals(listOf("serie"), TopRecentRanking.rankCandidates(listOf(series), today).map { it.id })
    }

    private val api = object : MainAPI() {
        override var name = "FS"
        override val supportedTypes = setOf(TvType.Movie)
        override suspend fun search(query: String): List<SearchResponse> =
            if (query == "Le Robot sauvage") listOf(newMovieSearchResponse("Le Robot Sauvage (2024)", "https://fs/robot", TvType.Movie) { year = 2024 }) else emptyList()
    }

    private val robot = TopRecentRanking.Item("tt29623480", "Le Robot sauvage", null, 2024, 8.2, null, emptyList(), 300.0, 1.0, "movie", originalTitle = "The Wild Robot")

    @Test
    fun top10TitlesAreMatchedInTheAddonsUnderTheirLocalTitle(): Unit = runBlocking {
        TopAvailability.clearCache()
        val found = TopAvailability.searchIn(api, robot)
        assertEquals("https://fs/robot", found?.url)
        // Another year or another kind of title is not the same one.
        assertFalse(TopAvailability.sameTitle(api.newMovieSearchResponse("Le Robot sauvage", "u", TvType.Movie) { year = 1980 }, robot))
        assertFalse(TopAvailability.sameTitle(api.newTvSeriesSearchResponse("Le Robot sauvage", "u", TvType.TvSeries), robot))
        assertTrue(TopAvailability.sameTitle(api.newMovieSearchResponse("The Wild Robot", "u", TvType.Movie), robot))
        // Titles nobody has are dropped.
        val unknown = robot.copy(id = "tt0", title = "Introuvable", originalTitle = null)
        val entries = TopAvailability.available(listOf(unknown, robot), listOf(api), checkSources = false)
        assertEquals(listOf("tt29623480"), entries.map { it.item.id })
    }

    /** A site whose page "/robot" has a video and "/vide" none. */
    private val site = object : MainAPI() {
        override var name = "Site"
        override val supportedTypes = setOf(TvType.Movie)
        override suspend fun search(query: String): List<SearchResponse> = listOf(
            newMovieSearchResponse("Le Robot sauvage", "https://site/robot", TvType.Movie) { year = 2024 },
        )
        override suspend fun load(url: String) = newMovieLoadResponse("Film", url, TvType.Movie, url.substringAfterLast('/')) {}
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
            callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
        ): Boolean {
            if (data == "robot") callback(com.lagradost.cloudstream3.utils.newExtractorLink("Site", "HD", "https://cdn/robot.m3u8"))
            return true
        }
    }

    @Test
    fun top10KeepsOnlyTitlesWithARealSource(): Unit = runBlocking {
        TopAvailability.clearCache()
        val withVideo = newMovieSearchResponseOf("https://site/robot")
        val withoutVideo = newMovieSearchResponseOf("https://site/vide")
        assertTrue(TopAvailability.hasSource(site, withVideo))
        assertFalse(TopAvailability.hasSource(site, withoutVideo))
        assertEquals(listOf("tt29623480"), TopAvailability.available(listOf(robot), listOf(site)).map { it.item.id })
    }

    private fun newMovieSearchResponseOf(url: String) = site.newMovieSearchResponse("Le Robot sauvage", url, TvType.Movie)

    @Test
    fun dubbedTrailerIsRecognised() {
        val entries = listOf(
            Trailers.SearchEntry("eeeeeeeeeee", "Le Robot Sauvage - Extrait \"La stratégie du mort\" VF [Actuellement au cinéma]", 46.0),
            Trailers.SearchEntry("aaaaaaaaaaa", "LE ROBOT SAUVAGE Bande Annonce VOSTFR (2024)", 150.0),
            Trailers.SearchEntry("bbbbbbbbbbb", "Dune Bande Annonce VF", 140.0),
            Trailers.SearchEntry("ccccccccccc", "LE ROBOT SAUVAGE - Bande-annonce VF (2024)", 2000.0),
            Trailers.SearchEntry("ddddddddddd", "Le Robot Sauvage | Bande-annonce officielle VF | DreamWorks", 132.0),
        )
        assertEquals("ddddddddddd", Trailers.pickLocalized(entries, "Le Robot sauvage", "fr"))
        assertNull(Trailers.pickLocalized(entries.take(4), "Le Robot sauvage", "fr"))
        assertNull(Trailers.pickLocalized(entries, "Le Robot sauvage", "en"), "English: the original version is used")
    }

    @Test
    fun repositoryShortCodesFollowTheShortenerRedirection(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val location = when (exchange.requestURI.path) {
                "/cspr" -> "https://raw.githubusercontent.com/user/repo/main/repo.json"
                "/abc" -> "cloudstreamrepo://raw.githubusercontent.com/other/repo/main/repo.json"
                else -> "$base/404"
            }
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            RepositoryManager.cuttlyBase = base
            RepositoryManager.pymdBase = base
            assertEquals("https://raw.githubusercontent.com/user/repo/main/repo.json", RepositoryManager.parseRepoUrl(" cspr "))
            assertEquals("https://raw.githubusercontent.com/other/repo/main/repo.json", RepositoryManager.parseRepoUrl("!abc"))
            assertNull(RepositoryManager.parseRepoUrl("inconnu"))
            // Addresses are not short codes.
            assertEquals("https://example.com/repo.json", RepositoryManager.parseRepoUrl("example.com/repo.json"))
            assertTrue(RepositoryManager.isShortCode("ab_c-1") && !RepositoryManager.isShortCode("a.b/c"))
        } finally {
            RepositoryManager.cuttlyBase = "https://cutt.ly"
            RepositoryManager.pymdBase = "https://py.md"
            server.stop(0)
        }
    }
}
