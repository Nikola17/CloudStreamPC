package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File

/** Plays links in mpv (bundled with the Windows build) or VLC, passing the headers the hosts require. */
object ExternalPlayer {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    class PlayerNotFound(kind: PlayerKind) : Exception(
        when (kind) {
            PlayerKind.MPV -> "mpv est introuvable. Indiquez son chemin dans Paramètres."
            PlayerKind.VLC -> "VLC est introuvable. Installez VLC ou indiquez son chemin dans Paramètres."
            PlayerKind.CUSTOM -> "Le lecteur personnalisé est introuvable. Vérifiez son chemin dans Paramètres."
        }
    )

    fun isPlayable(link: ExtractorLink) =
        link.type != ExtractorLinkType.TORRENT && link.type != ExtractorLinkType.MAGNET

    /**
     * Starts the player.
     * @param startAt position to resume from, in seconds
     * @param ipcPath mpv IPC server path, used to follow the playback position
     */
    fun play(
        link: ExtractorLink,
        title: String,
        subtitles: List<SubtitleFile>,
        startAt: Double? = null,
        ipcPath: String? = null,
    ): Process {
        val settings = Store.settings
        val kind = settings.player
        val executable = findPlayer(kind, settings.playerPath) ?: throw PlayerNotFound(kind)
        val headers = link.getAllHeaders()
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: USER_AGENT
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value

        val command = mutableListOf(executable.absolutePath)
        when (kind) {
            PlayerKind.MPV -> {
                command += "--force-window=immediate"
                command += "--force-media-title=$title"
                if (settings.fullscreen) command += "--fs"
                if (startAt != null) command += "--start=+${startAt.toInt()}"
                if (ipcPath != null) command += "--input-ipc-server=$ipcPath"
                settings.subtitleLanguages.takeIf { it.isNotBlank() }?.let { command += "--slang=$it" }
                settings.audioLanguages.takeIf { it.isNotBlank() }?.let { command += "--alang=$it" }
                command += "--sub-font-size=${settings.subtitleSize}"
                command += "--user-agent=$userAgent"
                if (referer != null) command += "--referrer=$referer"
                headers.filterKeys { !it.equals("user-agent", true) && !it.equals("referer", true) }
                    .forEach { (k, v) -> command += "--http-header-fields-append=$k: $v" }
                subtitles.forEach { command += "--sub-file=${it.url}" }
                command += "--"
                command += link.url
            }
            PlayerKind.VLC -> {
                command += "--meta-title=$title"
                if (settings.fullscreen) command += "--fullscreen"
                command += link.url
                if (startAt != null) command += ":start-time=${startAt.toInt()}"
                command += ":http-user-agent=$userAgent"
                if (referer != null) command += ":http-referrer=$referer"
                subtitles.firstOrNull()?.let { command += ":input-slave=${it.url}" }
            }
            PlayerKind.CUSTOM -> command += link.url
        }
        Logger.info("Lecture : ${link.name} ${link.url}")
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(File(com.cloudstreampc.core.AppDirs.logs, "player.log"))
            .start()
    }

    fun findPlayer(kind: PlayerKind, customPath: String?): File? {
        customPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        if (kind == PlayerKind.CUSTOM) return null
        val exe = when (kind) {
            PlayerKind.MPV -> if (isWindows) "mpv.exe" else "mpv"
            else -> if (isWindows) "vlc.exe" else "vlc"
        }
        val candidates = mutableListOf<File>()
        if (kind == PlayerKind.MPV) {
            // Bundled with the Windows installer (see build.gradle.kts / app-resources).
            System.getProperty("compose.application.resources.dir")?.let { candidates += File(it, "mpv/$exe") }
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { candidates += File(it, exe) }
        if (isWindows) {
            val programFiles = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            val home = System.getProperty("user.home")
            val localAppData = System.getenv("LOCALAPPDATA")
            if (kind == PlayerKind.MPV) {
                programFiles.forEach { candidates += File(it, "mpv/mpv.exe") }
                localAppData?.let { candidates += File(it, "Programs/mpv/mpv.exe") }
                candidates += File(home, "scoop/apps/mpv/current/mpv.exe")
                candidates += File("C:/ProgramData/chocolatey/bin/mpv.exe")
            } else {
                programFiles.forEach { candidates += File(it, "VideoLAN/VLC/vlc.exe") }
            }
        } else {
            candidates += listOf(File("/usr/bin/$exe"), File("/usr/local/bin/$exe"), File("/opt/homebrew/bin/$exe"))
            if (kind == PlayerKind.VLC) candidates += File("/Applications/VLC.app/Contents/MacOS/VLC")
        }
        return candidates.firstOrNull { it.isFile }
    }
}
