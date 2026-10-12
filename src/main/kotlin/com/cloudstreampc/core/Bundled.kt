package com.cloudstreampc.core

import java.io.File

/** Programs shipped next to the app (TorrServer, yt-dlp). */
object Bundled {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * A copy we are allowed to run: on Linux and macOS the installed file may have lost its execute
     * bit in a folder we cannot change (/opt, the .app bundle), so it is copied to the cache first.
     */
    fun runnable(file: File): File {
        if (isWindows || file.canExecute() || file.setExecutable(true)) return file
        val copy = File(File(AppDirs.cache, "bin").apply { mkdirs() }, file.name)
        if (copy.length() != file.length()) file.copyTo(copy, overwrite = true)
        copy.setExecutable(true)
        return copy
    }
}
