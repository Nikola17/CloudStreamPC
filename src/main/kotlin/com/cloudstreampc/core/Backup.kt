package com.cloudstreampc.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup / restore of settings, profiles, favorites, history and downloads list (like the app). */
object Backup {
    private fun dataFiles(): List<File> =
        AppDirs.root.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()

    val folder: File get() = Store.settings.backupDir?.let(::File)?.apply { mkdirs() } ?: AppDirs.backups

    fun create(target: File? = null): File {
        val name = "CloudStreamPC-sauvegarde-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))}.zip"
        val file = target ?: File(folder, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            dataFiles().forEach { data ->
                zip.putNextEntry(ZipEntry(data.name))
                data.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Store.updateSettings { it.copy(lastBackup = System.currentTimeMillis()) }
        // Keep the 10 latest automatic backups.
        folder.listFiles { f -> f.name.startsWith("CloudStreamPC-sauvegarde-") }?.sortedByDescending { it.lastModified() }
            ?.drop(10)?.forEach { it.delete() }
        Logger.info("Sauvegarde créée : ${file.path}")
        return file
    }

    fun restore(file: File) {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = File(entry.name).name
                if (!entry.isDirectory && name.endsWith(".json")) {
                    File(AppDirs.root, name).outputStream().use { zip.copyTo(it) }
                }
            }
        }
        Store.reload()
        Logger.info("Sauvegarde restaurée : ${file.path}")
    }

    /** Automatic backup at startup when the chosen frequency has elapsed. */
    fun autoBackupIfDue() {
        val days = Store.settings.backupFrequencyDays
        if (days <= 0) return
        if (System.currentTimeMillis() - Store.settings.lastBackup > days * 86_400_000L) {
            runCatching { create() }.onFailure { Logger.error("Sauvegarde automatique", it) }
        }
    }
}
