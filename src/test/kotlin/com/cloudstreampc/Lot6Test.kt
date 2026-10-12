package com.cloudstreampc

import com.cloudstreampc.core.AndroidImport
import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.StatusEntry
import com.cloudstreampc.core.UserLibrary
import com.cloudstreampc.core.WatchStatus
import com.cloudstreampc.core.mapper
import com.cloudstreampc.sync.FolderSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Lot6Test {
    /** The shape of a CloudStream Android backup: every value is JSON text. */
    private fun backup(): String {
        fun json(value: Any) = mapper.writeValueAsString(value)
        val strings = mapOf(
            "0/result_watch_state_data/101" to json(mapOf("bookmarkedTime" to 5, "id" to 101, "latestUpdatedTime" to 5, "name" to "Frieren", "url" to "https://fm/frieren", "apiName" to "French-Manga", "type" to "Anime", "posterUrl" to "https://img/f.jpg", "year" to 2023)),
            "0/result_watch_state/101" to "0",
            "0/result_watch_state_data/102" to json(mapOf("bookmarkedTime" to 7, "id" to 102, "latestUpdatedTime" to 7, "name" to "Dune", "url" to "https://fs/dune", "apiName" to "French-Stream", "type" to "Movie", "posterUrl" to null, "year" to 2021)),
            "0/result_watch_state/102" to "4",
            "0/result_watch_state_data/103" to json(mapOf("bookmarkedTime" to 1, "id" to 103, "latestUpdatedTime" to 1, "name" to "Sans statut", "url" to "https://x", "apiName" to "X", "type" to "Movie", "posterUrl" to null, "year" to null)),
            "0/result_watch_state/103" to "5",
            "0/result_favorites_state_data/201" to json(mapOf("favoritesTime" to 9, "id" to 201, "latestUpdatedTime" to 9, "name" to "Arcane", "url" to "https://fs/arcane", "apiName" to "French-Stream", "type" to "TvSeries", "posterUrl" to "https://img/a.jpg", "year" to 2021)),
            "0/result_resume_watching_2/301" to json(mapOf("parentId" to 301, "episodeId" to 99, "episode" to 4, "season" to 2, "updateTime" to 50, "isFromDownload" to false)),
            "download_header_cache/301" to json(mapOf("apiName" to "French-Stream", "url" to "https://fs/arcane", "type" to "TvSeries", "name" to "Arcane", "poster" to "https://img/a.jpg", "cacheTime" to 1, "id" to 301)),
            "REPOSITORIES_KEY" to json(listOf(mapOf("iconUrl" to null, "name" to "FR", "url" to "https://repo/fr.json"))),
        )
        return json(mapOf("datastore" to mapOf("_String" to strings), "settings" to mapOf("_Bool" to mapOf("x" to true))))
    }

    @Test
    fun readsAnAndroidBackup() {
        val imported = AndroidImport.parse(backup())
        assertEquals(
            listOf("Frieren" to WatchStatus.WATCHING, "Dune" to WatchStatus.PLAN_TO_WATCH),
            imported.statuses.sortedBy { it.updatedAt }.map { it.item.name to it.status },
        )
        assertEquals(listOf("Arcane"), imported.favorites.map { it.name })
        assertEquals("S2·E4", imported.history.single().episodeLabel)
        assertEquals("https://fs/arcane", imported.history.single().item.url)
        assertEquals(listOf("https://repo/fr.json"), imported.repositories.map { it.url })
        assertTrue(imported.summary().contains("2 titre(s)"))
    }

    @Test
    fun syncsStatusesBetweenComputers() {
        val dune = SavedItem("Dune", "u1", "FS")
        val local = UserLibrary(statuses = mapOf("FS|u1" to StatusEntry(dune, WatchStatus.WATCHING, 10)))
        val remote = UserLibrary(
            statuses = mapOf("FS|u1" to StatusEntry(dune, WatchStatus.COMPLETED, 20), "FS|u2" to StatusEntry(SavedItem("Alien", "u2", "FS"), WatchStatus.DROPPED, 5)),
            statusRemoved = mapOf("FS|u2" to 8),
        )
        val merged = FolderSync.merge(local, remote)
        assertEquals(mapOf("FS|u1" to WatchStatus.COMPLETED), merged.statuses.mapValues { it.value.status })
    }
}
