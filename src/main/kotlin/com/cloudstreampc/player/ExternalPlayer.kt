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

    fun isPlayable(link: ExtractorLink) = !TorrentEngine.isTorrent(link) || TorrentEngine.available

    /**
     * Starts the player.
     * @param startAt position to resume from, in seconds
     * @param ipcPath mpv IPC server path, used to follow the playback position
     * @param hasNext an episode follows: the player offers it at the end
     */
    fun play(
        link: ExtractorLink,
        title: String,
        subtitles: List<SubtitleFile>,
        startAt: Double? = null,
        ipcPath: String? = null,
        hasNext: Boolean = false,
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
                command += interfaceArgs(hasNext)
                settings.subtitleLanguages.takeIf { it.isNotBlank() }?.let { command += "--slang=$it" }
                settings.audioLanguages.takeIf { it.isNotBlank() }?.let { command += "--alang=$it" }
                command += "--sub-font-size=${settings.subtitleSize}"
                if (settings.playbackSpeed != 1.0) command += "--speed=${settings.playbackSpeed}"
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

    /** Folder of the mpv scripts shipped with the app (uosc and our buttons). */
    fun scriptsDir(): File? =
        System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv") }
            ?.takeIf { File(it, "scripts/cloudstreampc.lua").isFile }

    /**
     * Modern controls (uosc) plus our "Passer l'intro" / "Épisode suivant" buttons,
     * when the player is mpv and the interface is enabled.
     */
    fun interfaceArgs(hasNext: Boolean): List<String> {
        val settings = Store.settings
        val dir = scriptsDir()
        if (settings.player != PlayerKind.MPV || !settings.playerInterface || dir == null) return emptyList()
        val controls = buildList {
            add("menu")
            add("gap")
            add("<video,audio>subtitles")
            add("<has_many_audio>audio")
            add("<has_many_video>video")
            add("<stream>stream-quality")
            add("gap")
            add("space")
            add("<video,audio>speed")
            add("space")
            if (settings.skip85Button) add("command:fast_forward:seek 85?Avancer de 85 s")
            if (hasNext) add("command:skip_next:script-message-to cloudstreampc next?Épisode suivant (N)")
            add("gap")
            add("fullscreen")
        }.joinToString(",")
        val accent = String.format("%06x", settings.primaryColor and 0xFFFFFF)
        fun yesNo(value: Boolean) = if (value) "yes" else "no"
        return listOf(
            "--osc=no",
            "--osd-bar=no",
            // Our script decides what happens at the end: next episode or quit.
            "--keep-open=yes",
            "--script=${File(dir, "scripts/uosc").path}",
            "--script=${File(dir, "scripts/cloudstreampc.lua").path}",
            "--osd-fonts-dir=${File(dir, "fonts").path}",
            "--script-opts-append=uosc-languages=fr,en",
            "--script-opts-append=uosc-controls=$controls",
            "--script-opts-append=uosc-color=foreground=$accent,foreground_text=ffffff",
            "--script-opts-append=uosc-timeline_style=bar",
            "--script-opts-append=cloudstreampc-has_next=${yesNo(hasNext)}",
            "--script-opts-append=cloudstreampc-auto_next=${yesNo(settings.autoPlayNextEpisode)}",
            "--script-opts-append=cloudstreampc-auto_skip=${yesNo(settings.autoSkipIntro)}",
            "--script-opts-append=cloudstreampc-skip_button=${yesNo(settings.skipIntroButton)}",
        )
    }

    /** Plays a YouTube trailer, with sound (mpv uses yt-dlp to read YouTube). */
    fun playTrailer(youtubeUrl: String, title: String) {
        val settings = Store.settings
        val executable = findPlayer(settings.player, settings.playerPath) ?: throw PlayerNotFound(settings.player)
        val command = mutableListOf(executable.absolutePath)
        if (settings.player == PlayerKind.MPV) {
            command += "--force-media-title=$title"
            if (settings.fullscreen) command += "--fs"
            command += interfaceArgs(hasNext = false)
            com.cloudstreampc.providers.Trailers.ytDlp()?.let { command += "--script-opts-append=ytdl_hook-ytdl_path=$it" }
            command += "--ytdl-format=bv*[height<=1080]+ba/b"
            command += "--"
        } else if (settings.player == PlayerKind.VLC && settings.fullscreen) {
            command += "--fullscreen"
        }
        command += youtubeUrl
        ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(File(com.cloudstreampc.core.AppDirs.logs, "player.log")).start()
    }

    /** Plays a downloaded file (no headers needed). */
    fun playFile(file: File, title: String) {
        val settings = Store.settings
        val executable = findPlayer(settings.player, settings.playerPath) ?: throw PlayerNotFound(settings.player)
        val command = mutableListOf(executable.absolutePath)
        when (settings.player) {
            PlayerKind.MPV -> {
                command += "--force-media-title=$title"
                if (settings.fullscreen) command += "--fs"
                command += interfaceArgs(hasNext = false)
                command += "--"
            }
            PlayerKind.VLC -> if (settings.fullscreen) command += "--fullscreen"
            PlayerKind.CUSTOM -> Unit
        }
        command += file.absolutePath
        ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(File(com.cloudstreampc.core.AppDirs.logs, "player.log")).start()
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
