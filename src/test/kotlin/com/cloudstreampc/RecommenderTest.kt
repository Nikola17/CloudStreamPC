package com.cloudstreampc

import com.cloudstreampc.core.RecItem
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.TasteData
import com.cloudstreampc.core.TasteSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommenderTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun rec(name: String, api: String = "FS", type: String = "Movie", rating: Double? = null) =
        RecItem(name, "https://$api/${name.replace(' ', '-')}", api, null, type, 2020, rating)

    private fun seed(name: String, recs: List<RecItem>, weight: Double = 1.0, daysAgo: Long = 0, type: String = "Movie") =
        TasteSeed("FS", "https://FS/$name", name, null, type, emptyList(), recs, weight, now - daysAgo * day)

    @Test
    fun titleSuggestedByManyWatchedTitlesComesFirst() {
        val taste = TasteData(
            listOf(
                seed("Batman", listOf(rec("Joker"), rec("Superman"), rec("Inception"))),
                seed("Dark Knight", listOf(rec("Inception"), rec("Joker"))),
                seed("Tenet", listOf(rec("Inception"), rec("Interstellar"))),
            )
        )
        val result = Recommender.recommend(taste, emptySet(), now)
        assertEquals("Inception", result.first().item.name)
        assertEquals(3, result.first().because.size)
        assertEquals("Joker", result[1].item.name)
    }

    @Test
    fun skipsWatchedDismissedAndSeedTitles() {
        val joker = rec("Joker")
        val taste = TasteData(
            listOf(seed("Batman", listOf(joker, rec("Superman"), rec("Batman [VF]"), rec("Inception")))),
            dismissed = setOf("FS|${rec("Superman").url}"),
        )
        val result = Recommender.recommend(taste, excluded = setOf("FS|${joker.url}"), now = now).map { it.item.name }
        assertEquals(listOf("Inception"), result)
    }

    @Test
    fun abandonedAndOldTitlesWeighLess() {
        val taste = TasteData(
            listOf(
                seed("Recent", listOf(rec("A")), weight = 3.0, daysAgo = 1),
                seed("Old", listOf(rec("B")), weight = 3.0, daysAgo = 120),
                seed("Abandoned", listOf(rec("C")), weight = 0.0),
            )
        )
        val result = Recommender.recommend(taste, emptySet(), now).map { it.item.name }
        assertEquals(listOf("A", "B"), result)
    }

    @Test
    fun sameTitleFromTwoProvidersIsMerged() {
        val taste = TasteData(
            listOf(
                seed("X", listOf(rec("Dune", api = "FS"))),
                seed("Y", listOf(rec("Dune [VF]", api = "Movix"), rec("Other"))),
            )
        )
        val result = Recommender.recommend(taste, emptySet(), now)
        assertEquals(1, result.count { it.item.name.startsWith("Dune") })
        assertEquals("Dune", result.first().item.name.substringBefore(" ["))
    }

    @Test
    fun seriesWatchersGetSeriesFirst() {
        val taste = TasteData(
            listOf(
                seed("Show 1", listOf(rec("Movie A"), rec("Series A", type = "TvSeries")), weight = 3.0, type = "TvSeries"),
                seed("Show 2", listOf(rec("Movie B"), rec("Series B", type = "TvSeries")), weight = 3.0, type = "TvSeries"),
            )
        )
        val result = Recommender.recommend(taste, emptySet(), now).map { it.item.name }
        assertTrue(result.indexOf("Series A") < result.indexOf("Movie A"))
    }

    @Test
    fun becauseRowsNeedEnoughTitles() {
        val many = (1..6).map { rec("T$it") }
        val taste = TasteData(listOf(seed("Liked", many, weight = 2.0), seed("Few", many.take(2), weight = 2.0, daysAgo = 2)))
        val rows = Recommender.becauseRows(taste, emptySet())
        assertEquals(listOf("Liked"), rows.map { it.seed.name })
        assertFalse(rows.first().items.any { it.name == "Liked" })
    }
}
