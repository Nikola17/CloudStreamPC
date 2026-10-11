package com.cloudstreampc.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class RepositoryEntry(
    val name: String = "",
    val url: String = "",
)

data class InstalledPlugin(
    val internalName: String = "",
    val name: String = "",
    val version: Int = 0,
    val url: String = "",
    val repositoryUrl: String? = null,
    val description: String? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val authors: List<String> = emptyList(),
)

enum class PlayerKind { MPV, VLC, CUSTOM }

data class Settings(
    val repositories: List<RepositoryEntry> = emptyList(),
    val installedPlugins: List<InstalledPlugin> = emptyList(),
    val player: PlayerKind = PlayerKind.MPV,
    val playerPath: String? = null,
    val homeProvider: String? = null,
    val autoUpdatePlugins: Boolean = true,
    val firstLaunchDone: Boolean = false,
    /** Highest quality to pick automatically (e.g. 1080); null picks the best available. */
    val preferredQuality: Int? = null,
    /** Skip the source list and play the best source right away. */
    val autoPlayBestSource: Boolean = false,
    val autoPlayNextEpisode: Boolean = true,
    val resumePlayback: Boolean = true,
    val fullscreen: Boolean = true,
    /** Comma separated languages given to mpv (`--slang` / `--alang`). */
    val subtitleLanguages: String = "fr,fre,fra,french",
    val audioLanguages: String = "fr,fre,fra,french",
    val subtitleSize: Int = 55,
)

/** Playback position of a movie or episode, in seconds. */
data class WatchProgress(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val updatedAt: Long = 0,
) {
    val fraction: Float get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val finished: Boolean get() = duration > 0 && position / duration >= WATCHED_THRESHOLD

    companion object {
        const val WATCHED_THRESHOLD = 0.9
    }
}

/** Enough information to reopen a title with `MainAPI.load(url)`. */
data class SavedItem(
    val name: String = "",
    val url: String = "",
    val apiName: String = "",
    val posterUrl: String? = null,
    val type: String? = null,
    val year: Int? = null,
)

data class HistoryEntry(
    val item: SavedItem = SavedItem(),
    val episodeLabel: String? = null,
    val episodeData: String? = null,
    val timestamp: Long = 0,
)

data class UserLibrary(
    val favorites: List<SavedItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    /** Keys built by [Store.watchedKey]. */
    val watched: Set<String> = emptySet(),
    /** Resume positions, keyed like [watched]. */
    val progress: Map<String, WatchProgress> = emptyMap(),
)

/** Persistent app state, observable from Compose. */
object Store {
    var settings by mutableStateOf(readJsonFile(AppDirs.settingsFile) { Settings() })
        private set
    var library by mutableStateOf(readJsonFile(AppDirs.libraryFile) { UserLibrary() })
        private set

    @Synchronized
    fun updateSettings(block: (Settings) -> Settings) {
        settings = block(settings)
        writeJsonFile(AppDirs.settingsFile, settings)
    }

    @Synchronized
    fun updateLibrary(block: (UserLibrary) -> UserLibrary) {
        library = block(library)
        writeJsonFile(AppDirs.libraryFile, library)
    }

    fun isFavorite(apiName: String, url: String) =
        library.favorites.any { it.apiName == apiName && it.url == url }

    fun toggleFavorite(item: SavedItem) = updateLibrary { lib ->
        if (lib.favorites.any { it.apiName == item.apiName && it.url == item.url }) {
            lib.copy(favorites = lib.favorites.filterNot { it.apiName == item.apiName && it.url == item.url })
        } else {
            lib.copy(favorites = listOf(item) + lib.favorites)
        }
    }

    fun watchedKey(apiName: String, episodeData: String) = "$apiName|$episodeData"

    fun addHistory(item: SavedItem, episodeLabel: String?, episodeData: String) = updateLibrary { lib ->
        val entry = HistoryEntry(item, episodeLabel, episodeData, System.currentTimeMillis())
        lib.copy(
            history = (listOf(entry) + lib.history.filterNot { it.item.apiName == item.apiName && it.item.url == item.url })
                .take(200),
        )
    }

    fun progressOf(apiName: String, episodeData: String): WatchProgress? =
        library.progress[watchedKey(apiName, episodeData)]

    fun isWatched(apiName: String, episodeData: String) = watchedKey(apiName, episodeData) in library.watched

    /** Saves a resume position; past 90 % the movie/episode counts as watched and restarts from zero. */
    fun saveProgress(apiName: String, episodeData: String, position: Double, duration: Double) = updateLibrary { lib ->
        val key = watchedKey(apiName, episodeData)
        val progress = WatchProgress(position, duration, System.currentTimeMillis())
        if (progress.finished) {
            lib.copy(watched = lib.watched + key, progress = lib.progress - key)
        } else {
            // Keep the 500 most recent positions.
            val updated = (lib.progress + (key to progress)).entries
                .sortedByDescending { it.value.updatedAt }.take(500).associate { it.key to it.value }
            lib.copy(progress = updated)
        }
    }

    fun setWatched(apiName: String, episodeData: String, watched: Boolean) = updateLibrary { lib ->
        val key = watchedKey(apiName, episodeData)
        lib.copy(
            watched = if (watched) lib.watched + key else lib.watched - key,
            progress = lib.progress - key,
        )
    }

    fun removeHistory(entry: HistoryEntry) = updateLibrary { lib ->
        lib.copy(history = lib.history - entry)
    }

    fun clearHistory() = updateLibrary { it.copy(history = emptyList()) }
}
