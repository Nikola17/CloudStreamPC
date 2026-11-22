package com.moncloudi.core

import java.io.File

/**
 * "Lancer au démarrage de l'ordinateur": MonCloudi starts hidden in the notification area, so
 * new-episode notifications and downloads keep going. Only for the installed app (not `gradle run`).
 */
object Autostart {
    private val os = System.getProperty("os.name").lowercase()
    const val HIDDEN_ARG = "--minimized"
    private const val NAME = "MonCloudi"

    /** The installed program (set by the launcher of the packaged app). */
    private val program: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    val supported: Boolean get() = program != null

    fun apply(enabled: Boolean) = runCatching {
        val exe = program ?: return@runCatching
        when {
            "win" in os -> {
                val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                val command = if (enabled) listOf("reg", "add", key, "/v", NAME, "/t", "REG_SZ", "/d", "\"$exe\" $HIDDEN_ARG", "/f")
                else listOf("reg", "delete", key, "/v", NAME, "/f")
                ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
                // Entry of the versions named CloudStreamPC (their program no longer exists).
                ProcessBuilder("reg", "delete", key, "/v", AppDirs.LEGACY_NAME, "/f").redirectErrorStream(true).start().waitFor()
            }
            "mac" in os -> {
                File(System.getProperty("user.home"), "Library/LaunchAgents/io.github.nikola17.cloudstreampc.plist").delete()
                val plist = File(System.getProperty("user.home"), "Library/LaunchAgents/io.github.nikola17.moncloudi.plist")
                if (enabled) {
                    plist.parentFile.mkdirs()
                    plist.writeText(
                        """<?xml version="1.0" encoding="UTF-8"?>
                        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        |<plist version="1.0"><dict>
                        |<key>Label</key><string>io.github.nikola17.moncloudi</string>
                        |<key>ProgramArguments</key><array><string>$exe</string><string>$HIDDEN_ARG</string></array>
                        |<key>RunAtLoad</key><true/>
                        |</dict></plist>
                        |""".trimMargin(),
                    )
                } else {
                    plist.delete()
                }
            }
            else -> {
                val autostart = File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "autostart")
                File(autostart, "cloudstreampc.desktop").delete()
                val desktop = File(autostart, "moncloudi.desktop")
                if (enabled) {
                    desktop.parentFile.mkdirs()
                    desktop.writeText("[Desktop Entry]\nType=Application\nName=MonCloudi\nExec=\"$exe\" $HIDDEN_ARG\nX-GNOME-Autostart-enabled=true\n")
                } else {
                    desktop.delete()
                }
            }
        }
        Logger.info("Lancement au démarrage : ${if (enabled) "activé" else "désactivé"}")
    }.onFailure { Logger.error("Lancement au démarrage", it) }
}
