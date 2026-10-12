package com.cloudstreampc

import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.UserCollection
import com.cloudstreampc.core.UserLibrary
import com.cloudstreampc.core.mapper
import com.cloudstreampc.providers.Enrichment
import com.cloudstreampc.sync.FolderSync
import com.cloudstreampc.ui.EnglishTexts
import com.cloudstreampc.ui.I18n
import com.cloudstreampc.ui.mergeResults
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Lot4Test {
    private val api = object : MainAPI() { override var name = "Test" }

    @Test
    fun translatesExactTextsAndTemplates() {
        assertEquals("My List", I18n.toEnglish("Ma liste"))
        assertEquals("My List (12)", I18n.toEnglish("Ma liste (12)"))
        assertEquals("Resume S1·E3 (12:04)", I18n.toEnglish("Reprendre S1·E3 (12:04)"))
        assertEquals("Connected as Nikola", I18n.toEnglish("Connecté en tant que Nikola"))
        assertEquals("  Open folder", I18n.toEnglish("  Ouvrir le dossier"))
        // Content (titles, summaries) stays as it is.
        assertEquals("Le Comte de Monte-Cristo", I18n.toEnglish("Le Comte de Monte-Cristo"))
        // Every template has as many variable parts in English as in French.
        EnglishTexts.map.filterKeys { "{}" in it }.forEach { (fr, en) ->
            assertEquals(fr.split("{}").size, en.split("{}").size, "Template « $fr »")
        }
    }

    @Test
    fun mergesTheSameTitleFromSeveralProviders() {
        fun provider(id: String) = object : MainAPI() { override var name = id }
        fun movie(name: String, provider: String, year: Int?) =
            provider(provider).newMovieSearchResponse(name, "https://$provider/$name", TvType.Movie) { this.year = year }
        val results = listOf(
            movie("Dune", "A", 2021),
            movie("Dune : Deuxième partie", "A", 2024),
            movie("DUNE", "B", 2021),
            movie("Dune", "C", 1984),
            provider("D").newTvSeriesSearchResponse("Dune", "https://D/dune", TvType.TvSeries) { year = 2022 },
            movie("Dune", "B", 2021), // same provider twice: kept once
        )
        val merged = mergeResults(results).map { group -> group.map { it.apiName } }
        assertEquals(listOf("A", "B", "D"), merged.first(), "2021 and 2022 (one year apart) are the same title")
        assertTrue(listOf("C") in merged && listOf("A") in merged)
        assertEquals(3, merged.size)
    }

    @Test
    fun readsCinemetaDetails() {
        val meta = mapper.readTree(
            """{"imdbRating":"8.0","director":["Denis Villeneuve"],"cast":["Timothée Chalamet","Zendaya"],
               "background":"https://img/bg.jpg","logo":"","description":"Paul Atreides…",
               "videos":[{"season":1,"episode":2,"name":"Ep 2","overview":"Second","thumbnail":"https://img/2.jpg"},{"id":"x"}]}""",
        )
        val enriched = Enrichment.fromCinemeta(meta)
        assertEquals("8.0", enriched.imdbRating)
        assertEquals(listOf("Denis Villeneuve"), enriched.directors)
        assertEquals(listOf("Timothée Chalamet", "Zendaya"), enriched.cast.map { it.name })
        assertEquals(null, enriched.logo)
        assertEquals("Second", enriched.episode(1, 2)?.overview)
        assertEquals("Second", enriched.episode(null, 2)?.overview)
        assertEquals(1, enriched.episodes.size)
    }

    @Test
    fun syncsCollectionsBetweenComputers() {
        val a1 = SavedItem("Alien", "u1", "FS")
        val a2 = SavedItem("Aliens", "u2", "FS")
        val local = UserLibrary(
            collections = listOf(
                UserCollection("c1", "Soirée SF", listOf(a1), modifiedAt = 10),
                UserCollection("c2", "À jeter", emptyList(), modifiedAt = 5),
            ),
        )
        val remote = UserLibrary(
            collections = listOf(
                UserCollection("c1", "Soirée SF", listOf(a1, a2), modifiedAt = 20),
                UserCollection("c3", "Animés", emptyList(), modifiedAt = 7),
            ),
            collectionsRemoved = mapOf("c2" to 8),
        )
        val merged = FolderSync.merge(local, remote).collections
        assertEquals(listOf("c1", "c3"), merged.map { it.id })
        assertEquals(listOf("Alien", "Aliens"), merged.first().items.map { it.name })
        assertTrue(FolderSync.sameContent(FolderSync.merge(local, remote), FolderSync.merge(remote, local)))
    }
}
