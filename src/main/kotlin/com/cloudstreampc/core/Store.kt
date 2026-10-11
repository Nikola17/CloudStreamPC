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

/** A user profile: its own favorites, history and progress, like CloudStream's accounts. */
data class Profile(
    val id: String = "default",
    val name: String = "Défaut",
    /** ARGB color of the avatar. */
    val color: Long = 0xFF3D50FA,
    /** Optional 4-digit code asked when switching to this profile. */
    val pin: String? = null,
)

enum class IptvType { XTREAM, M3U }

/** An IPTV subscription: Xtream Codes (server + username + password) or an M3U playlist. */
data class IptvAccount(
    val id: String = "",
    val name: String = "",
    val type: IptvType = IptvType.XTREAM,
    /** Xtream server (http://host:port) or M3U playlist URL. */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
)

/** A Stremio addon, identified by its manifest URL. */
data class StremioAddonEntry(
    val manifestUrl: String = "",
    val name: String = "",
    val enabled: Boolean = true,
)

data class Settings(
    val repositories: List<RepositoryEntry> = emptyList(),
    val installedPlugins: List<InstalledPlugin> = emptyList(),
    val player: PlayerKind = PlayerKind.MPV,
    val playerPath: String? = null,
    val homeProvider: String? = null,
    val autoUpdatePlugins: Boolean = true,
    val firstLaunchDone: Boolean = false,
    /** Bumped when a default changes and existing settings must follow it. */
    val settingsVersion: Int = 0,

    // Lecteur
    /** Highest quality to pick automatically (e.g. 1080); null picks the best available. */
    val preferredQuality: Int? = null,
    /** Play the best source right away instead of showing the list. */
    val autoPlayBestSource: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val resumePlayback: Boolean = true,
    val fullscreen: Boolean = true,
    val playbackSpeed: Double = 1.0,
    /** Comma separated languages given to mpv (`--slang` / `--alang`). */
    val subtitleLanguages: String = "fr,fre,fra,french",
    val audioLanguages: String = "fr,fre,fra,french",
    val subtitleSize: Int = 55,

    // Fournisseurs
    /** Languages of the extensions to show; empty shows all. */
    val providerLanguages: Set<String> = emptySet(),
    /** TvType names shown on the home page; empty shows all. */
    val preferredMedia: Set<String> = emptySet(),
    val showDubSubBadge: Boolean = true,
    val nsfw: Boolean = false,

    // Disposition
    val primaryColor: Long = 0xFF3D50FA,
    val theme: String = "black",
    /** 0 small, 1 normal, 2 large. */
    val posterSize: Int = 1,
    val titleBelowPoster: Boolean = true,
    val showQualityBadge: Boolean = true,
    val showScoreBadge: Boolean = true,
    val showCast: Boolean = true,
    val showHero: Boolean = true,
    val randomButton: Boolean = false,
    val showClock: Boolean = false,
    val confirmExit: Boolean = false,
    val searchHistoryEnabled: Boolean = true,

    // Général
    /** DNS over HTTPS provider id (see `Network`), "none" uses the system DNS. */
    val dns: String = "none",
    val downloadDir: String? = null,
    val parallelDownloads: Int = 2,

    // Sauvegarde
    /** Days between automatic backups, 0 disables them. */
    val backupFrequencyDays: Int = 0,
    val backupDir: String? = null,
    val lastBackup: Long = 0,
    val checkAppUpdates: Boolean = true,

    // Comptes et sécurité
    val profiles: List<Profile> = listOf(Profile()),
    val currentProfile: String = "default",
    val askProfileAtStartup: Boolean = false,
    /** Code asked when the app starts. */
    val appPin: String? = null,
    val iptvAccounts: List<IptvAccount> = emptyList(),
    val stremioAddons: List<StremioAddonEntry> = emptyList(),
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
    val searchHistory: List<String> = emptyList(),
)

private const val SETTINGS_VERSION = 2

/** Persistent app state, observable from Compose. */
object Store {
    var settings by mutableStateOf(migrate(readJsonFile(AppDirs.settingsFile) { Settings() }))
        private set
    var library by mutableStateOf(readJsonFile(AppDirs.libraryFile(settings.currentProfile)) { UserLibrary() })
        private set

    val profile: Profile
        get() = settings.profiles.firstOrNull { it.id == settings.currentProfile } ?: settings.profiles.first()

    private fun migrate(loaded: Settings): Settings {
        var s = loaded
        if (s.settingsVersion < 2) {
            // v2: the best source always plays directly, like the user asked.
            s = s.copy(autoPlayBestSource = true)
        }
        if (s.profiles.isEmpty()) s = s.copy(profiles = listOf(Profile()), currentProfile = "default")
        return s.copy(settingsVersion = SETTINGS_VERSION)
    }

    @Synchronized
    fun switchProfile(id: String) {
        updateSettings { it.copy(currentProfile = id) }
        library = readJsonFile(AppDirs.libraryFile(id)) { UserLibrary() }
    }

    /** Reloads everything from disk (after restoring a backup). */
    @Synchronized
    fun reload() {
        settings = migrate(readJsonFile(AppDirs.settingsFile) { Settings() })
        library = readJsonFile(AppDirs.libraryFile(settings.currentProfile)) { UserLibrary() }
    }

    fun addSearchQuery(query: String) {
        if (!settings.searchHistoryEnabled || query.isBlank()) return
        updateLibrary { lib -> lib.copy(searchHistory = (listOf(query) + lib.searchHistory.filterNot { it.equals(query, true) }).take(30)) }
    }

    @Synchronized
    fun updateSettings(block: (Settings) -> Settings) {
        settings = block(settings)
        writeJsonFile(AppDirs.settingsFile, settings)
    }

    @Synchronized
    fun updateLibrary(block: (UserLibrary) -> UserLibrary) {
        library = block(library)
        writeJsonFile(AppDirs.libraryFile(settings.currentProfile), library)
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
