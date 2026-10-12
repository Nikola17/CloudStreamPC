package com.cloudstreampc

import com.cloudstreampc.core.Parental
import com.cloudstreampc.core.Profile
import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.Stats
import com.cloudstreampc.core.StatusEntry
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.UserLibrary
import com.cloudstreampc.core.WatchEvent
import com.cloudstreampc.core.WatchStatus
import com.cloudstreampc.core.mapper
import com.cloudstreampc.download.DownloadManager
import com.cloudstreampc.download.Throttle
import com.cloudstreampc.plugins.ExtensionHealth
import com.cloudstreampc.providers.Enrichment
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Lot8Test {
    @Test
    fun readsAgeRatings() {
        assertEquals(12, Parental.ageOf("-12"))
        assertEquals(13, Parental.ageOf("PG-13"))
        assertEquals(18, Parental.ageOf("TV-MA"))
        assertEquals(16, Parental.ageOf("FSK 16"))
        assertEquals(0, Parental.ageOf("Tous publics"))
        assertEquals(10, Parental.ageOf("PG"))
        assertNull(Parental.ageOf("NR"))
        assertNull(Parental.ageOf(null))

        val child = Profile(id = "k", kids = true, maxAge = 12)
        assertTrue(Parental.allowed(child, TvType.Movie, "PG"))
        assertFalse(Parental.allowed(child, TvType.Movie, "R"))
        // The extension's rating counts first, then TMDB's.
        assertTrue(Parental.allowed(child, TvType.Movie, null, "-10"))
        assertTrue(Parental.allowed(child, TvType.Movie, null, null))
        assertFalse(Parental.allowed(child.copy(blockUnrated = true), TvType.Movie, null, null))
        assertFalse(Parental.allowed(child, TvType.NSFW, "0"))
        assertTrue(Parental.allowed(Profile(), TvType.Movie, "18"))
    }

    @Test
    fun parentalCodeToLeaveAChildProfile() {
        val before = Store.settings
        try {
            Store.updateSettings {
                it.copy(
                    profiles = listOf(Profile(id = "default"), Profile(id = "kid", kids = true), Profile(id = "own", pin = "9999")),
                    currentProfile = "kid", parentalPin = "1234",
                )
            }
            assertEquals("1234", Parental.codeFor(Store.settings.profiles[0]))
            assertEquals("9999", Parental.codeFor(Store.settings.profiles[2]))
            assertNull(Parental.codeFor(Store.settings.profiles[1]))
            assertFalse(Parental.nsfwAllowed)
        } finally {
            Store.updateSettings { before }
        }
    }

    @Test
    fun offPeakHoursAndSubtitleLanguages() {
        assertTrue(DownloadManager.allowedAt(2, 1, 7))
        assertFalse(DownloadManager.allowedAt(8, 1, 7))
        // Across midnight: 23 h → 6 h.
        assertTrue(DownloadManager.allowedAt(23, 23, 6))
        assertTrue(DownloadManager.allowedAt(3, 23, 6))
        assertFalse(DownloadManager.allowedAt(12, 23, 6))
        assertTrue(DownloadManager.allowedAt(12, 5, 5))

        val subs = listOf(SubtitleFile("English", "https://s/en.srt"), SubtitleFile("Français", "https://s/fr.srt"), SubtitleFile("French (forced)", "https://s/fr2.vtt"))
        Store.updateSettings { it.copy(subtitleLanguages = "fr,fre,fra,french") }
        assertEquals(listOf("https://s/fr.srt", "https://s/fr2.vtt"), DownloadManager.preferredSubtitles(subs).map { it.url })
    }

    @Test
    fun speedLimitIsKept(): Unit = runBlocking {
        val before = Store.settings.downloadSpeedLimit
        try {
            Store.updateSettings { it.copy(downloadSpeedLimit = 1000) }
            Throttle.take(1000 * 1024) // the second of burst
            val start = System.nanoTime()
            repeat(10) { Throttle.take(100 * 1024) }
            val seconds = (System.nanoTime() - start) / 1e9
            println("1 Mo à 1000 Ko/s : $seconds s")
            assertTrue(seconds in 0.8..1.3, "débit non respecté : $seconds s")
        } finally {
            Store.updateSettings { it.copy(downloadSpeedLimit = before) }
        }
    }

    @Test
    fun yearSummary() {
        val events = listOf(
            WatchEvent("2026-03-01", "A", "u1", "Arcane", TvType.TvSeries.name, listOf("Animation", "Action"), 3600, "S1·E1"),
            WatchEvent("2026-03-02", "A", "u1", "Arcane", TvType.TvSeries.name, listOf("Animation", "Action"), 3000, "S1·E2"),
            WatchEvent("2026-03-03", "A", "u2", "Dune", TvType.Movie.name, listOf("Science-fiction"), 9000, null),
            WatchEvent("2026-05-10", "I", "c1", "TF1", TvType.Live.name, emptyList(), 1800, null),
            WatchEvent("2025-12-31", "A", "u3", "Ancien", TvType.Movie.name, listOf("Drame"), 5000, null),
        )
        val finishedAt = LocalDate.of(2026, 4, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val library = UserLibrary(
            statuses = mapOf("A|u1" to StatusEntry(SavedItem("Arcane", "u1", "A", type = TvType.TvSeries.name), WatchStatus.COMPLETED, finishedAt)),
        )
        val year = Stats.of(2026, events, library)
        assertEquals(3600L + 3000 + 9000 + 1800, year.seconds)
        assertEquals(1, year.movies)
        assertEquals(2, year.episodes)
        assertEquals(1800L, year.liveSeconds)
        assertEquals("Dune", year.topTitles.first().first)
        assertEquals(3, year.longestStreak)
        assertEquals(4, year.activeDays)
        assertEquals(1, year.finishedSeries)
        assertEquals(listOf(2026, 2025), Stats.years(events))
        assertEquals(4, Stats.of(null, events, library).titles)
    }

    @Test
    fun tmdbAgeRating() {
        val movie = mapper.readTree(
            """{"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG-13"}]},{"iso_3166_1":"FR","release_dates":[{"certification":""},{"certification":"12"}]}]}}""",
        )
        assertEquals("12", Enrichment.certificationOf(movie, "movie"))
        val tv = mapper.readTree("""{"content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-14"}]}}""")
        assertEquals("TV-14", Enrichment.certificationOf(tv, "tv"))
    }

    @Test
    fun brokenSitesAreSpotted(): Unit = runBlocking {
        val working = object : MainAPI() {
            override var name = "Marche"
            override suspend fun search(query: String): List<SearchResponse> = listOf(newMovieSearchResponse("Film", "https://x/film", TvType.Movie))
        }
        val broken = object : MainAPI() {
            override var name = "Cassé"
            override suspend fun search(query: String): List<SearchResponse> = throw java.io.IOException("HTTP 521")
        }
        assertTrue(ExtensionHealth.check(working).ok)
        val result = ExtensionHealth.check(broken)
        assertFalse(result.ok)
        assertEquals("HTTP 521", result.detail)
    }
}
