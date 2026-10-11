package com.cloudstreampc

import com.cloudstreampc.core.HistoryEntry
import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.TasteData
import com.cloudstreampc.core.TasteSeed
import com.cloudstreampc.core.UserLibrary
import com.cloudstreampc.core.WatchProgress
import com.cloudstreampc.sync.FolderSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderSyncTest {
    private val batman = SavedItem("Batman", "u1", "FS")
    private val narcos = SavedItem("Narcos", "u2", "FS")
    private val frieren = SavedItem("Frieren", "u3", "FM")

    @Test
    fun keepsBothComputersChanges() {
        // PC A: Batman favorite since t=10, watched episode e1; PC B: added Narcos, removed Batman at t=20.
        val a = UserLibrary(
            favorites = listOf(batman),
            favoriteAdded = mapOf("FS|u1" to 10),
            history = listOf(HistoryEntry(batman, timestamp = 100), HistoryEntry(frieren, timestamp = 50)),
            watched = setOf("FS|e1"),
            progress = mapOf("FS|e2" to WatchProgress(100.0, 1000.0, 30), "FS|e1" to WatchProgress(10.0, 1000.0, 5)),
            searchHistory = listOf("batman"),
        )
        val b = UserLibrary(
            favorites = listOf(narcos),
            favoriteAdded = mapOf("FS|u2" to 15, "FS|u1" to 10),
            favoriteRemoved = mapOf("FS|u1" to 20),
            history = listOf(HistoryEntry(batman, timestamp = 200), HistoryEntry(narcos, timestamp = 150)),
            historyRemoved = mapOf("FM|u3" to 60),
            progress = mapOf("FS|e2" to WatchProgress(400.0, 1000.0, 40)),
            searchHistory = listOf("narcos", "Batman"),
        )
        val merged = FolderSync.merge(a, b)
        assertEquals(listOf("Narcos"), merged.favorites.map { it.name })
        assertEquals(listOf(200L, 150L), merged.history.map { it.timestamp }, "latest entry per title; Frieren removed on B")
        assertEquals(setOf("FS|e1"), merged.watched)
        assertEquals(400.0, merged.progress["FS|e2"]?.position)
        assertTrue("FS|e1" !in merged.progress, "a watched episode has no resume position")
        assertEquals(listOf("batman", "narcos"), merged.searchHistory)

        // The other computer ends up with the same data, so nothing is written back and forth.
        val other = FolderSync.merge(b, merged)
        assertTrue(FolderSync.sameContent(merged, other))
        assertTrue(FolderSync.sameContent(other, FolderSync.merge(merged, other)))
    }

    @Test
    fun mergesTastes() {
        val old = TasteSeed("FS", "u1", "Batman", weight = 2.0, lastSeen = 10)
        val recent = old.copy(weight = 5.0, lastSeen = 20)
        val other = TasteSeed("FS", "u2", "Narcos", weight = 1.0, lastSeen = 15)
        val merged = FolderSync.merge(TasteData(listOf(old), setOf("x")), TasteData(listOf(recent, other), setOf("y")))
        assertEquals(listOf(5.0, 1.0), merged.seeds.map { it.weight })
        assertEquals(setOf("x", "y"), merged.dismissed)
    }
}
