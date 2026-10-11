package com.cloudstreampc.sync

import com.cloudstreampc.core.HistoryEntry
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.Taste
import com.cloudstreampc.core.TasteData
import com.cloudstreampc.core.UserLibrary
import com.cloudstreampc.core.readJsonFile
import com.cloudstreampc.core.writeJsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress

/** What one computer puts in the shared folder for a profile. */
data class SyncSnapshot(
    val device: String = "",
    val savedAt: Long = 0,
    val library: UserLibrary = UserLibrary(),
    val taste: TasteData = TasteData(),
)

/**
 * Keeps favorites, history, progress and tastes the same on several computers through a folder
 * they share (OneDrive, Google Drive, Dropbox, network drive…): each change is merged into
 * one file per profile, and the other computers merge it back.
 */
object FolderSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    @Volatile private var lastSeenRemote = 0L

    private val device: String by lazy { runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("PC") }

    fun file(dir: File, profile: String) = File(dir, "CloudStreamPC-sync-$profile.json")

    private fun folder(): File? = Store.settings.syncDir?.let(::File)?.takeIf { it.isDirectory }

    fun start() {
        Store.onLibraryChanged = ::scheduleExport
        Taste.onChanged = ::scheduleExport
        scope.launch {
            while (true) {
                runCatching { importIfChanged() }.onFailure { Logger.error("Synchronisation", it) }
                delay(2 * 60_000)
            }
        }
    }

    /** Waits a little so a burst of changes (progress every 10 s) is written once. */
    private fun scheduleExport() {
        if (folder() == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(20_000)
            runCatching { syncNow() }.onFailure { Logger.error("Synchronisation", it) }
        }
    }

    /** Merges the shared file into this computer, then writes the result back. Returns a short status. */
    @Synchronized
    fun syncNow(): String {
        val dir = folder() ?: return "Aucun dossier de synchronisation."
        val target = file(dir, Store.settings.currentProfile)
        val remote = if (target.isFile) readJsonFile<SyncSnapshot?>(target) { null } else null
        if (remote != null) apply(remote)
        // Nothing new for the other computers: don't touch the file (they would merge it again).
        val unchanged = remote != null && sameContent(remote.library, Store.library) &&
            remote.taste.seeds.toSet() == Taste.current().seeds.toSet() && remote.taste.dismissed == Taste.current().dismissed
        if (!unchanged) writeJsonFile(target, SyncSnapshot(device, System.currentTimeMillis(), Store.library, Taste.current()))
        lastSeenRemote = target.lastModified()
        return if (remote != null && remote.device != device) "Synchronisé avec ${remote.device}" else "Synchronisé"
    }

    private fun importIfChanged() {
        val dir = folder() ?: return
        val target = file(dir, Store.settings.currentProfile)
        if (!target.isFile || target.lastModified() <= lastSeenRemote) return
        syncNow()
        Logger.info("Synchronisation : bibliothèque fusionnée depuis le dossier partagé")
    }

    private fun apply(remote: SyncSnapshot) {
        val merged = merge(Store.library, remote.library)
        if (merged != Store.library) Store.replaceLibrary(merged)
        val taste = merge(Taste.current(), remote.taste)
        if (taste != Taste.current()) Taste.replace(taste)
    }

    private fun key(apiName: String, url: String) = "$apiName|$url"

    /** Same synced data, whatever the order and the local-only fields (new episodes, dates). */
    fun sameContent(a: UserLibrary, b: UserLibrary): Boolean {
        fun normalized(lib: UserLibrary) = lib.copy(
            favorites = emptyList(), searchHistory = emptyList(), newEpisodes = emptyList(), modifiedAt = 0,
        )
        return normalized(a) == normalized(b) && a.favorites.toSet() == b.favorites.toSet() &&
            a.searchHistory.map { it.lowercase() }.toSet() == b.searchHistory.map { it.lowercase() }.toSet()
    }

    /** Both computers' changes are kept; for the same title the most recent one wins. */
    fun merge(local: UserLibrary, remote: UserLibrary): UserLibrary {
        fun maxMerge(a: Map<String, Long>, b: Map<String, Long>) = (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }
        val added = maxMerge(local.favoriteAdded, remote.favoriteAdded)
        val removed = maxMerge(local.favoriteRemoved, remote.favoriteRemoved)
        val favorites = (local.favorites + remote.favorites).distinctBy { key(it.apiName, it.url) }.filter {
            val k = key(it.apiName, it.url)
            (added[k] ?: 0) >= (removed[k] ?: -1)
        }

        val historyRemoved = maxMerge(local.historyRemoved, remote.historyRemoved)
        val history = (local.history + remote.history)
            .groupBy { key(it.item.apiName, it.item.url) }
            .mapNotNull { (k, entries) -> entries.maxBy(HistoryEntry::timestamp).takeIf { it.timestamp > (historyRemoved[k] ?: -1) } }
            .sortedByDescending { it.timestamp }
            .take(200)

        val watched = local.watched + remote.watched
        val progress = (local.progress.keys + remote.progress.keys).filter { it !in watched }.associateWith { k ->
            listOfNotNull(local.progress[k], remote.progress[k]).maxBy { it.updatedAt }
        }
        val snapshots = (local.episodeSnapshots.keys + remote.episodeSnapshots.keys).associateWith { k ->
            listOfNotNull(local.episodeSnapshots[k], remote.episodeSnapshots[k]).maxBy { it.checkedAt }
        }
        return local.copy(
            favorites = favorites,
            favoriteAdded = added,
            favoriteRemoved = removed,
            history = history,
            historyRemoved = historyRemoved,
            watched = watched,
            progress = progress,
            searchHistory = (local.searchHistory + remote.searchHistory).distinctBy { it.lowercase() }.take(30),
            episodeSnapshots = snapshots,
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
        )
    }

    fun merge(local: TasteData, remote: TasteData): TasteData {
        val seeds = (local.seeds + remote.seeds).groupBy { key(it.apiName, it.url) }
            .map { (_, seeds) -> seeds.maxBy { it.lastSeen } }
            .sortedByDescending { it.lastSeen }
            .take(300)
        return TasteData(seeds, local.dismissed + remote.dismissed)
    }
}
