package com.moncloudi.core

import java.io.File

object AppDirs {
    /** %APPDATA%\MonCloudi on Windows, ~/Library/Application Support/MonCloudi on macOS, ~/.local/share/MonCloudi elsewhere. */
    val root: File by lazy {
        System.getProperty("moncloudi.home")?.let { return@lazy File(it).apply { mkdirs() } }
        val appData = System.getenv("APPDATA")
        val home = System.getProperty("user.home")
        val base = if (!appData.isNullOrBlank()) {
            File(appData)
        } else if (System.getProperty("os.name").lowercase().contains("mac")) {
            File(home, "Library/Application Support")
        } else {
            File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share")
        }
        File(base, "MonCloudi").also { migrateFromOldName(File(base, LEGACY_NAME), it) }.apply { mkdirs() }
    }

    /** Name of the app (and of its data folder) before version 3.1. */
    const val LEGACY_NAME = "CloudStreamPC"

    /**
     * Before 3.1 the data was in a "CloudStreamPC" folder: the first start under the new name takes
     * it over (library, settings, extensions...), so nothing is lost by the update.
     */
    internal fun migrateFromOldName(old: File, new: File) {
        if (!old.isDirectory || (new.isDirectory && !new.list().isNullOrEmpty())) return
        if (new.isDirectory) new.delete()
        if (runCatching { old.renameTo(new) }.getOrDefault(false)) return
        // Another volume or a file in use: copy instead, the old folder stays as a backup.
        runCatching { old.copyRecursively(new, overwrite = false) }
    }

    val plugins: File get() = File(root, "plugins").apply { mkdirs() }
    val logs: File get() = File(root, "logs").apply { mkdirs() }
    val settingsFile: File get() = File(root, "settings.json")
    /** The default profile keeps the original file name. */
    fun libraryFile(profileId: String): File =
        File(root, if (profileId == "default") "library.json" else "library-$profileId.json")

    val backups: File get() = File(root, "backups").apply { mkdirs() }
    val cache: File get() = File(root, "cache").apply { mkdirs() }
}
