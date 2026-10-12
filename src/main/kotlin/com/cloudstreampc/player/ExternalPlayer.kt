package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File
import kotlinx.coroutines.launch

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
        live: Boolean = false,
        wid: Long? = null,
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
                command += "--force-media-title=$title"
                command += windowArgs(wid)
                if (startAt != null) command += "--start=+${startAt.toInt()}"
                if (ipcPath != null) command += "--input-ipc-server=$ipcPath"
                command += interfaceArgs(hasNext, live, embedded = wid != null)
                if (live) command += liveArgs()
                settings.subtitleLanguages.takeIf { it.isNotBlank() }?.let { command += "--slang=$it" }
                settings.audioLanguages.takeIf { it.isNotBlank() }?.let { command += "--alang=$it" }
                command += subtitleArgs()
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
    /**
     * Live TV: keep [Settings.liveBuffer] seconds ahead (a cut shorter than that is never seen),
     * reconnect by itself if the stream drops, and skip damaged packets instead of stopping.
     */
    fun liveArgs(): List<String> {
        val buffer = Store.settings.liveBuffer.coerceIn(2, 60)
        return listOf(
            "--cache=yes",
            "--cache-pause=yes",
            "--cache-pause-initial=yes",
            "--cache-pause-wait=$buffer",
            "--demuxer-max-bytes=256MiB",
            "--demuxer-max-back-bytes=64MiB",
            "--demuxer-readahead-secs=${maxOf(60, buffer * 4)}",
            "--network-timeout=20",
            "--hls-bitrate=max",
            "--demuxer-lavf-o-append=fflags=+discardcorrupt",
            "--demuxer-lavf-analyzeduration=1.5",
            "--demuxer-lavf-probesize=2000000",
            "--stream-lavf-o-append=reconnect=1",
            "--stream-lavf-o-append=reconnect_streamed=1",
            "--stream-lavf-o-append=reconnect_on_network_error=1",
            "--stream-lavf-o-append=reconnect_delay_max=4",
        )
    }

    fun interfaceArgs(hasNext: Boolean, live: Boolean = false, embedded: Boolean = false): List<String> {
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
            if (!live) add("<video,audio>speed")
            add("space")
            if (settings.skip85Button && !live) add("command:fast_forward:seek 85?Avancer de 85 s")
            if (hasNext) add("command:skip_next:script-message-to cloudstreampc next?Épisode suivant (N)")
            if (live) {
                add("command:skip_previous:script-message-to cloudstreampc zap -1?Chaîne précédente (Page préc.)")
                add("command:list:script-message-to cloudstreampc channels?Chaînes (C)")
                add("command:skip_next:script-message-to cloudstreampc zap 1?Chaîne suivante (Page suiv.)")
            }
            add("gap")
            add("command:picture_in_picture_alt:script-message-to cloudstreampc pip?Image dans l'image (Ctrl+P)")
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
            // uosc's own menus: its translations (fr, de, es…), English otherwise.
            "--script-opts-append=uosc-languages=" + com.cloudstreampc.ui.I18n.language.let { if (it == "en") "en" else "$it,en" },
            "--script-opts-append=uosc-controls=$controls",
            "--script-opts-append=uosc-color=foreground=$accent,foreground_text=ffffff",
            "--script-opts-append=uosc-timeline_style=bar",
            "--script-opts-append=cloudstreampc-has_next=${yesNo(hasNext)}",
            "--script-opts-append=cloudstreampc-live=${yesNo(live)}",
            "--script-opts-append=cloudstreampc-embedded=${yesNo(embedded)}",
            "--script-opts-append=cloudstreampc-auto_next=${yesNo(settings.autoPlayNextEpisode)}",
            "--script-opts-append=cloudstreampc-auto_skip=${yesNo(settings.autoSkipIntro)}",
            "--script-opts-append=cloudstreampc-skip_button=${yesNo(settings.skipIntroButton)}",
        )
    }

    /** Plays a YouTube trailer, with sound (mpv uses yt-dlp to read YouTube). */
    fun playTrailer(youtubeUrl: String, title: String) {
        val settings = Store.settings
        val executable = findPlayer(settings.player, settings.playerPath) ?: throw PlayerNotFound(settings.player)
        if (settings.player != PlayerKind.MPV) return startOther(executable, youtubeUrl)
        val options = mutableListOf("--force-media-title=$title")
        com.cloudstreampc.providers.Trailers.ytDlp()?.let { options += "--script-opts-append=ytdl_hook-ytdl_path=$it" }
        options += "--ytdl-format=bv*[height<=1080]+ba/b"
        startMpv(executable, options, youtubeUrl, title)
    }

    /** Plays a downloaded file (no headers needed). */
    fun playFile(file: File, title: String) {
        val settings = Store.settings
        val executable = findPlayer(settings.player, settings.playerPath) ?: throw PlayerNotFound(settings.player)
        if (settings.player != PlayerKind.MPV) return startOther(executable, file.absolutePath)
        startMpv(executable, listOf("--force-media-title=$title") + subtitleArgs(), file.absolutePath, title)
    }

    /** Any address mpv can read (tests use `av://lavfi:` pictures). */
    internal fun playTarget(target: String, title: String) {
        val executable = findPlayer(PlayerKind.MPV, Store.settings.playerPath) ?: throw PlayerNotFound(PlayerKind.MPV)
        startMpv(executable, listOf("--force-media-title=$title"), target, title)
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** mpv for a trailer or a file: in the app's window when possible, otherwise in its own. */
    private fun startMpv(executable: File, options: List<String>, target: String, title: String) {
        fun start(wid: Long?) {
            val ipcPath = MpvIpc.newServerPath()
            val command = listOf(executable.absolutePath) + windowArgs(wid) + "--input-ipc-server=$ipcPath" +
                interfaceArgs(hasNext = false, embedded = wid != null) + options + "--" + target
            val process = ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(File(com.cloudstreampc.core.AppDirs.logs, "player.log")).start()
            if (wid != null) PlayerSurface.follow(process, ipcPath)
            scope.launch { applySubtitleStyle(ipcPath, process) }
        }
        if (!PlayerSurface.enabled) return start(null)
        scope.launch {
            runCatching { start(PlayerSurface.acquire(title)) }.onFailure { Logger.error("Lecteur", it) }
        }
    }

    private fun startOther(executable: File, target: String) {
        val command = mutableListOf(executable.absolutePath)
        if (Store.settings.player == PlayerKind.VLC && Store.settings.fullscreen) command += "--fullscreen"
        command += target
        ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(File(com.cloudstreampc.core.AppDirs.logs, "player.log")).start()
    }

    /** Own window (full screen if asked), or drawn inside the app's window ([wid]). */
    private fun windowArgs(wid: Long?): List<String> = when {
        wid != null -> listOf("--wid=$wid", "--force-window=immediate")
        Store.settings.fullscreen -> listOf("--force-window=immediate", "--fs")
        else -> listOf("--force-window=immediate")
    }

    /** Subtitle look from "Sous-titres" in the settings (the options every mpv version knows). */
    fun subtitleArgs(): List<String> {
        val s = Store.settings
        return buildList {
            add("--sub-font-size=${s.subtitleSize}")
            add("--sub-color=${mpvColor(s.subtitleColor)}")
            add("--sub-pos=${s.subtitlePosition.coerceIn(0, 150)}")
            if (s.subtitleBold) add("--sub-bold=yes")
            if (s.subtitleDelay != 0.0) add("--sub-delay=${s.subtitleDelay}")
        }
    }

    /**
     * Outline and background: their option names changed in mpv 0.38 (sub-border-* became
     * sub-outline-*, the box became sub-border-style), so they are set once mpv runs, trying the new
     * names first; an unknown option on the command line would stop mpv from starting.
     */
    fun subtitleStyleCommands(): List<List<Any>> {
        val s = Store.settings
        val outline = mpvColor(s.subtitleOutlineColor)
        val box = s.subtitleBackground.coerceIn(0, 100)
        return buildList {
            add(listOf("set_property", "sub-outline-color", outline))
            add(listOf("set_property", "sub-border-color", outline))
            add(listOf("set_property", "sub-outline-size", s.subtitleOutlineSize))
            add(listOf("set_property", "sub-border-size", s.subtitleOutlineSize))
            if (box > 0) {
                val alpha = (box * 255 / 100).coerceIn(0, 255)
                add(listOf("set_property", "sub-back-color", "#%02X000000".format(alpha)))
                add(listOf("set_property", "sub-border-style", "background-box"))
            }
        }
    }

    suspend fun applySubtitleStyle(ipcPath: String, process: Process) {
        val s = Store.settings
        val defaults = s.subtitleOutlineColor == 0xFF000000L && s.subtitleOutlineSize == 3.0 && s.subtitleBackground == 0
        if (defaults) return
        kotlinx.coroutines.delay(300)
        if (process.isAlive) MpvIpc.send(ipcPath, *subtitleStyleCommands().toTypedArray())
    }

    private fun mpvColor(argb: Long) = "#%08X".format(argb and 0xFFFFFFFFL)

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
            if (kind == PlayerKind.MPV) candidates += File("/Applications/mpv.app/Contents/MacOS/mpv")
            if (kind == PlayerKind.VLC) candidates += File("/Applications/VLC.app/Contents/MacOS/VLC")
        }
        return candidates.firstOrNull { it.isFile }
    }
}
