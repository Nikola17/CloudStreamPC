package com.cloudstreampc

import com.cloudstreampc.core.mapper
import com.cloudstreampc.player.ExternalPlayer
import com.cloudstreampc.providers.Enrichment
import com.cloudstreampc.providers.TopRecentRanking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonCloudiFeaturesTest {

    @Test
    fun topRecentRankingBlendsRecencyPopularityAndRatingAndFiltersJunk() {
        val today = LocalDate.of(2026, 11, 22)
        val candidates = listOf(
            // Future unreleased movie -> MUST be excluded
            TopRecentRanking.Item("tt_future", "Unreleased Hype", null, 2027, 9.2, LocalDate.of(2027, 3, 1), listOf("Sci-Fi"), 800.0, 0.0, "movie"),
            // Very recent but terrible rating (4.0) -> MUST be excluded
            TopRecentRanking.Item("tt_bad", "Chroniques Ratées", null, 2026, 4.0, LocalDate.of(2026, 11, 10), listOf("Drama"), 300.0, 0.0, "movie"),
            // Old classic (2015) -> MUST be excluded from recent Top 10
            TopRecentRanking.Item("tt_old", "Classic 2015", null, 2015, 8.8, LocalDate.of(2015, 6, 1), listOf("Action"), 400.0, 0.0, "movie"),
            // Recent, very popular, great rating -> #1!
            TopRecentRanking.Item("tt_hit", "Great Recent Hit", null, 2026, 8.4, LocalDate.of(2026, 10, 15), listOf("Action", "Drama"), 480.0, 0.0, "movie"),
            // Older (10 months ago), decent rating, lower popularity -> lower rank
            TopRecentRanking.Item("tt_older", "Decent Earlier Film", null, 2026, 6.8, LocalDate.of(2026, 1, 20), listOf("Comedy"), 30.0, 0.0, "movie"),
            // Very recent, solid rating, solid popularity -> #2
            TopRecentRanking.Item("tt_good", "Fresh Solid Thriller", null, 2026, 7.6, LocalDate.of(2026, 11, 1), listOf("Thriller"), 160.0, 0.0, "movie"),
        )

        val ranked = TopRecentRanking.rankCandidates(candidates, today)
        val ids = ranked.map { it.id }

        assertFalse("tt_future" in ids, "Unreleased future movies must not appear in Top 10")
        assertFalse("tt_bad" in ids, "Low-rated movies (4.0) must not appear in Top 10")
        assertFalse("tt_old" in ids, "Old movies must not appear in recent Top 10")
        assertEquals(listOf("tt_hit", "tt_good", "tt_older"), ids)
    }

    @Test
    fun similarRankingPrioritizesGenreDirectorAndCastMatch() {
        val json = """
            [
              {"id":"tt_self","name":"Inception","genres":["Action","Sci-Fi","Thriller"],"director":["Christopher Nolan"],"cast":["Leonardo DiCaprio"],"releaseInfo":"2010","imdbRating":"8.8"},
              {"id":"tt_best","name":"Interstellar","genres":["Adventure","Drama","Sci-Fi"],"director":["Christopher Nolan"],"cast":["Matthew McConaughey"],"releaseInfo":"2014","imdbRating":"8.7"},
              {"id":"tt_genre","name":"Shutter Island","genres":["Mystery","Thriller"],"director":["Martin Scorsese"],"cast":["Leonardo DiCaprio"],"releaseInfo":"2010","imdbRating":"8.2"},
              {"id":"tt_multi","name":"Tenet","genres":["Action","Sci-Fi","Thriller"],"director":["Christopher Nolan"],"cast":["John David Washington"],"releaseInfo":"2020","imdbRating":"7.3"},
              {"id":"tt_unrelated","name":"Random Romcom","genres":["Romance","Comedy"],"director":["Someone Else"],"cast":["Actor X"],"releaseInfo":"1995","imdbRating":"6.0"}
            ]
        """.trimIndent()
        val nodes = mapper.readTree(json).toList()

        val ranked = Enrichment.rankSimilarNodes(
            candidates = nodes,
            targetId = "tt_self",
            targetNormalizedTitle = "inception",
            targetGenres = setOf("action", "sci-fi", "thriller"),
            targetDirectors = setOf("christopher nolan"),
            targetCast = setOf("leonardo dicaprio"),
            targetYear = 2010,
        )

        assertFalse(ranked.any { it.title == "Inception" }, "Current title must be excluded")
        assertEquals("Tenet", ranked.first().title, "Exact genre + director match should rank #1")
        assertEquals("Random Romcom", ranked.last().title, "Unrelated film should rank last")
    }

    @Test
    fun playerPerformanceVodAndErgonomicControlsConfigured() {
        val perf = ExternalPlayer.performanceArgs()
        assertTrue("--hwdec=auto-safe" in perf)
        assertTrue("--vo=gpu-next,gpu" in perf)

        val vod = ExternalPlayer.vodArgs()
        assertTrue("--cache=yes" in vod)
        assertTrue("--demuxer-readahead-secs=30" in vod)
        assertTrue("--stream-lavf-o-append=reconnect=1" in vod)

        val iface = ExternalPlayer.interfaceArgs(hasNext = true, live = false, embedded = false)
        val controlsOpt = iface.firstOrNull { it.startsWith("--script-opts-append=uosc-controls=") }
        assertTrue(controlsOpt != null, "uosc-controls must be generated when scriptsDir is present")
        val controls = controlsOpt.substringAfter("uosc-controls=").split(",")
        // Back button first, then gap, play-pause, -10s, +10s
        assertTrue(controls[0].startsWith("command:arrow_back:"), "Back button must be first control")
        assertEquals("play-pause", controls[2])
        assertTrue(controls[3].startsWith("command:replay_10:"))
        assertTrue(controls[4].startsWith("command:forward_10:"))
    }
}
