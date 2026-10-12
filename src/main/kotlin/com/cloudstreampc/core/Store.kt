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
    /** Child profile: titles above [maxAge] and NSFW hidden; leaving it asks for the parental code. */
    val kids: Boolean = false,
    val maxAge: Int = 12,
    /** Titles without a known age rating are blocked too. */
    val blockUnrated: Boolean = false,
    /** Extensions hidden in this profile (names). */
    val hiddenProviders: Set<String> = emptySet(),
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
    /** User-Agent sent to this provider; empty = the app's default. */
    val userAgent: String = "",
)

/**
 * A tracking account (AniList, MyAnimeList, Simkl, Trakt). The client id/secret belong to the API
 * client the user registered on the service, the tokens to their account.
 */
data class TrackerAccount(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Epoch ms; 0 when unknown. */
    val expiresAt: Long = 0,
    val userName: String? = null,
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
    /** Subtitle look (ARGB colors), see "Sous-titres". */
    val subtitleColor: Long = 0xFFFFFFFF,
    val subtitleOutlineColor: Long = 0xFF000000,
    val subtitleOutlineSize: Double = 3.0,
    /** Dark box behind the text, opacity in % (0: none). */
    val subtitleBackground: Int = 0,
    /** mpv's sub-pos: 100 at the bottom, lower is higher on the picture. */
    val subtitlePosition: Int = 100,
    val subtitleBold: Boolean = false,
    /** Seconds, positive shows them later. */
    val subtitleDelay: Double = 0.0,
    /**
     * The video plays inside the app's window (Windows, Linux) instead of a window of its own.
     * Experimental, off by default (the v3.0 setting `embeddedPlayer`, on for everyone, is no longer read).
     */
    val playerInWindow: Boolean = false,
    /** Phone remote: a page on the local network that drives the player. */
    val remoteControl: Boolean = false,
    /** Modern controls (uosc) and the "Passer l'intro" / "Épisode suivant" buttons in the bundled mpv. */
    val playerInterface: Boolean = true,
    val skipIntroButton: Boolean = true,
    val autoSkipIntro: Boolean = false,
    /** "+85 s" button in the player controls, like CloudStream. */
    val skip85Button: Boolean = true,
    /** Magnet / torrent sources played through the bundled torrent engine. */
    val torrentsEnabled: Boolean = true,
    /** Subtitles looked up online (OpenSubtitles) and added to the player's list. */
    val onlineSubtitles: Boolean = true,
    /** Discord shows what is being watched ("Rich Presence"), with the user's Discord application id. */
    val discordPresence: Boolean = false,
    val discordAppId: String = "",
    /** Optional TMDB key: cast photos, similar titles and French episode summaries on details pages. */
    val tmdbApiKey: String = "",
    /** Game controllers drive the app and the player. */
    val gamepadEnabled: Boolean = true,
    /** Live TV: seconds kept ahead in the player (3 fast start, 8 balanced, 20 steadiest). */
    val liveBuffer: Int = 8,
    /** Live TV goes through the local relay that reconnects without cutting the picture. */
    val liveRelay: Boolean = true,
    /** Chromium browser driven for the sites that need one (empty: found automatically). */
    val browserPath: String = "",
    /** Closing the window keeps the app in the notification area (notifications, downloads continue). */
    val closeToTray: Boolean = true,
    val backgroundHintShown: Boolean = false,
    /** Starts with the computer, hidden in the notification area. */
    val launchAtStartup: Boolean = false,
    /** Interface language: "fr" or "en". */
    val language: String = "fr",

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
    /** "Recommandé pour vous" rows, computed from what is watched on this computer. */
    val recommendationsEnabled: Boolean = true,
    /** Muted trailer behind the home banner. */
    val heroTrailers: Boolean = true,

    // Général
    /** Before v3.0.3: DNS over HTTPS provider id, "none" for the system DNS (still read when one was chosen). */
    val dns: String = "none",
    /**
     * DNS over HTTPS provider id (see `Network`): "auto" asks Cloudflare and falls back to the system DNS
     * (gets around ISP blocking without breaking anything), "none" uses only the system DNS.
     */
    val dnsServer: String? = null,
    val downloadDir: String? = null,
    val parallelDownloads: Int = 2,
    /** KB/s shared by all downloads; 0: no limit. */
    val downloadSpeedLimit: Int = 0,
    /** Downloads only run between these hours (e.g. 1 h → 7 h). */
    val offPeakOnly: Boolean = false,
    val offPeakStart: Int = 1,
    val offPeakEnd: Int = 7,
    /** Subtitles in the preferred languages are saved next to the video. */
    val downloadSubtitles: Boolean = true,
    /** Looks for new episodes of followed series. */
    val checkNewEpisodes: Boolean = true,
    val newEpisodeNotifications: Boolean = true,

    // Sauvegarde
    /** Days between automatic backups, 0 disables them. */
    val backupFrequencyDays: Int = 0,
    val backupDir: String? = null,
    val lastBackup: Long = 0,
    val checkAppUpdates: Boolean = true,

    // Comptes et sécurité
    /** Code that unlocks the settings and the other profiles from a child profile. */
    val parentalPin: String? = null,
    val profiles: List<Profile> = listOf(Profile()),
    val currentProfile: String = "default",
    val askProfileAtStartup: Boolean = false,
    /** Code asked when the app starts. */
    val appPin: String? = null,
    val iptvAccounts: List<IptvAccount> = emptyList(),
    val stremioAddons: List<StremioAddonEntry> = emptyList(),
    /** Tracking accounts by service id ("anilist", "mal", "simkl", "trakt"). */
    val trackers: Map<String, TrackerAccount> = emptyMap(),
    /** Folder shared between computers (OneDrive, Google Drive…) to sync libraries; null disables it. */
    val syncDir: String? = null,
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
    /** Episode count last seen for each followed series, keyed by "apiName|url". */
    val episodeSnapshots: Map<String, EpisodeSnapshot> = emptyMap(),
    /** Series with episodes released since the user last looked. */
    val newEpisodes: List<NewEpisode> = emptyList(),
    /** When each favorite was added / removed ("apiName|url" -> epoch ms), to merge libraries between PCs. */
    val favoriteAdded: Map<String, Long> = emptyMap(),
    val favoriteRemoved: Map<String, Long> = emptyMap(),
    /** History entries removed by hand, so a synced PC doesn't bring them back. */
    val historyRemoved: Map<String, Long> = emptyMap(),
    /** Lists made by the user ("Pour le week-end", "Films d'horreur"…). */
    val collections: List<UserCollection> = emptyList(),
    /** Deleted collections (id -> epoch ms), so a synced PC doesn't bring them back. */
    val collectionsRemoved: Map<String, Long> = emptyMap(),
    /** Where each title stands, like CloudStream's library ("apiName|url" -> status). */
    val statuses: Map<String, StatusEntry> = emptyMap(),
    /** Statuses cleared by hand (key -> epoch ms), so a synced PC doesn't bring them back. */
    val statusRemoved: Map<String, Long> = emptyMap(),
    /** Source that played last for a title ("apiName|url" -> source name), tried first next time. */
    val preferredSources: Map<String, String> = emptyMap(),
    /** Last change of this library, for the sync folder. */
    val modifiedAt: Long = 0,
)

/** CloudStream's library lists. */
enum class WatchStatus(val label: String, val english: String) {
    WATCHING("En cours", "Watching"),
    PLAN_TO_WATCH("À voir", "Plan to watch"),
    ON_HOLD("En pause", "On hold"),
    DROPPED("Abandonné", "Dropped"),
    COMPLETED("Terminé", "Completed"),
    ;

    /** In the interface's language. */
    val shown: String get() = com.cloudstreampc.ui.I18n.tr("WatchStatus.$name").takeIf { !it.startsWith("WatchStatus.") } ?: label
}

data class StatusEntry(
    val item: SavedItem = SavedItem(),
    val status: WatchStatus = WatchStatus.WATCHING,
    val updatedAt: Long = 0,
)

data class UserCollection(
    val id: String = "",
    val name: String = "",
    val items: List<SavedItem> = emptyList(),
    val modifiedAt: Long = 0,
)

data class EpisodeSnapshot(
    val count: Int = 0,
    val lastLabel: String? = null,
    val checkedAt: Long = 0,
)

data class NewEpisode(
    val item: SavedItem = SavedItem(),
    /** e.g. "S2·E5". */
    val label: String = "",
    val added: Int = 1,
    val foundAt: Long = 0,
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
        Parental.unlocked = false
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
        val updated = block(library)
        if (updated === library) return
        library = updated.copy(modifiedAt = System.currentTimeMillis())
        writeJsonFile(AppDirs.libraryFile(settings.currentProfile), library)
        runCatching { onLibraryChanged?.invoke() }
    }

    /** Replaces the library with one merged from the sync folder (does not notify the sync again). */
    @Synchronized
    fun replaceLibrary(merged: UserLibrary) {
        library = merged
        writeJsonFile(AppDirs.libraryFile(settings.currentProfile), library)
    }

    fun isFavorite(apiName: String, url: String) =
        library.favorites.any { it.apiName == apiName && it.url == url }

    fun toggleFavorite(item: SavedItem) = updateLibrary { lib ->
        val key = "${item.apiName}|${item.url}"
        val now = System.currentTimeMillis()
        if (lib.favorites.any { it.apiName == item.apiName && it.url == item.url }) {
            lib.copy(
                favorites = lib.favorites.filterNot { it.apiName == item.apiName && it.url == item.url },
                favoriteRemoved = lib.favoriteRemoved + (key to now),
            )
        } else {
            lib.copy(favorites = listOf(item) + lib.favorites, favoriteAdded = lib.favoriteAdded + (key to now))
        }
    }

    fun statusOf(apiName: String, url: String): WatchStatus? = library.statuses["$apiName|$url"]?.status

    /** Puts a title in a library list, or takes it out (null). */
    fun setStatus(item: SavedItem, status: WatchStatus?) = updateLibrary { lib ->
        val key = "${item.apiName}|${item.url}"
        val now = System.currentTimeMillis()
        if (status == null) {
            lib.copy(statuses = lib.statuses - key, statusRemoved = lib.statusRemoved + (key to now))
        } else {
            lib.copy(statuses = lib.statuses + (key to StatusEntry(item, status, now)), statusRemoved = lib.statusRemoved - key)
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
        lib.copy(
            history = lib.history - entry,
            historyRemoved = lib.historyRemoved + ("${entry.item.apiName}|${entry.item.url}" to System.currentTimeMillis()),
        )
    }

    fun createCollection(name: String): UserCollection {
        val collection = UserCollection(java.util.UUID.randomUUID().toString().take(8), name.trim(), emptyList(), System.currentTimeMillis())
        updateLibrary { it.copy(collections = it.collections + collection) }
        return collection
    }

    fun renameCollection(id: String, name: String) = updateLibrary { lib ->
        lib.copy(collections = lib.collections.map { if (it.id == id) it.copy(name = name.trim(), modifiedAt = System.currentTimeMillis()) else it })
    }

    fun deleteCollection(id: String) = updateLibrary { lib ->
        lib.copy(collections = lib.collections.filterNot { it.id == id }, collectionsRemoved = lib.collectionsRemoved + (id to System.currentTimeMillis()))
    }

    /** Adds [item] to the collection, or removes it when it is already there. */
    fun toggleInCollection(id: String, item: SavedItem) = updateLibrary { lib ->
        lib.copy(collections = lib.collections.map { c ->
            if (c.id != id) return@map c
            val present = c.items.any { it.apiName == item.apiName && it.url == item.url }
            c.copy(
                items = if (present) c.items.filterNot { it.apiName == item.apiName && it.url == item.url } else listOf(item) + c.items,
                modifiedAt = System.currentTimeMillis(),
            )
        })
    }

    fun clearHistory() = updateLibrary { lib ->
        val now = System.currentTimeMillis()
        lib.copy(history = emptyList(), historyRemoved = lib.historyRemoved + lib.history.associate { "${it.item.apiName}|${it.item.url}" to now })
    }

    /** Called after every library change (sync folder). */
    @Volatile
    var onLibraryChanged: (() -> Unit)? = null
}
