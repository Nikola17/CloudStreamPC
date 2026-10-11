package com.cloudstreampc.core

import java.io.File

object AppDirs {
    /** %APPDATA%\CloudStreamPC on Windows, ~/.local/share/CloudStreamPC elsewhere. */
    val root: File by lazy {
        System.getProperty("cloudstreampc.home")?.let { return@lazy File(it).apply { mkdirs() } }
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) {
            File(appData)
        } else {
            File(System.getProperty("user.home"), ".local/share")
        }
        File(base, "CloudStreamPC").apply { mkdirs() }
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
