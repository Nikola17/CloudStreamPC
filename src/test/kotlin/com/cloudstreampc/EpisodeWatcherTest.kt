package com.cloudstreampc

import com.cloudstreampc.core.EpisodeWatcher
import com.cloudstreampc.core.HistoryEntry
import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.UserLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeWatcherTest {
    private val api = "Watcher-${System.nanoTime()}"
    private val series = SavedItem("MobLand", "https://site/mobland", api, null, "TvSeries")

    private fun releases() = Store.library.newEpisodes.filter { it.item.apiName == api }

    @Test
    fun detectsNewEpisodesAfterTheFirstLook() {
        // First check: only remembers the count.
        assertNull(EpisodeWatcher.record(series, 10, "S2·E5"))
        assertNull(EpisodeWatcher.record(series, 10, "S2·E5"))
        assertTrue(releases().isEmpty())

        val release = assertNotNull(EpisodeWatcher.record(series, 11, "S2·E6"))
        assertEquals("S2·E6", release.label)
        assertEquals(1, release.added)
        // Another one before the user looked: still one entry, two episodes.
        EpisodeWatcher.record(series, 12, "S2·E7")
        assertEquals(1, releases().size)
        assertEquals(2, releases().single().added)
        assertEquals("S2·E7", releases().single().label)

        EpisodeWatcher.seen(api, series.url)
        assertTrue(releases().isEmpty())
        assertEquals(12, Store.library.episodeSnapshots[EpisodeWatcher.key(api, series.url)]?.count)
    }

    @Test
    fun followsFavoriteAndRecentSeriesOnly() {
        val now = System.currentTimeMillis()
        val movie = SavedItem("Batman", "https://site/batman", api, null, "Movie")
        val old = SavedItem("Vieille série", "https://site/old", api, null, "TvSeries")
        val anime = SavedItem("Frieren", "https://site/frieren", api, null, "Anime")
        val library = UserLibrary(
            favorites = listOf(series, movie),
            history = listOf(
                HistoryEntry(anime, timestamp = now - 3600_000L),
                HistoryEntry(old, timestamp = now - 90L * 24 * 3600_000L),
                HistoryEntry(series, timestamp = now),
            ),
        )
        assertEquals(listOf("MobLand", "Frieren"), EpisodeWatcher.followed(library, now).map { it.name })
    }
}
