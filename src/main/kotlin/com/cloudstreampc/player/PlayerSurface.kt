package com.cloudstreampc.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * The video area inside the app's window (Windows and Linux): mpv draws into a native panel of the
 * window (`--wid`), so its controls, our buttons, the IPC and the live relay all stay the same.
 * Also handles full screen (the app's window goes full screen) and picture in picture (the window
 * shrinks to a small always-on-top player in a corner).
 */
object PlayerSurface {
    private val os = System.getProperty("os.name").lowercase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keys = Executors.newSingleThreadExecutor { Thread(it, "mpv-keys").apply { isDaemon = true } }

    /** macOS doesn't let another process draw inside a window: mpv keeps its own window there. */
    val supported = "win" in os || "linux" in os || "nux" in os

    val enabled get() = supported && Store.settings.embeddedPlayer && Store.settings.player == PlayerKind.MPV

    var active by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var pip by mutableStateOf(false)
        private set

    /** The app's main window, set by Main. */
    var window: WindowState? = null

    @Volatile private var handle: Long? = null
    @Volatile private var waiting: CompletableDeferred<Long>? = null
    @Volatile private var process: Process? = null
    @Volatile var ipcPath: String? = null
        private set

    /** Window size and place before full screen / picture in picture, restored afterwards. */
    private var saved: Triple<WindowPlacement, DpSize, WindowPosition>? = null

    /**
     * Shows the video area and returns the native id mpv draws into, or null when it can't be had
     * (mpv then opens its own window). The video that was playing there is stopped first.
     */
    suspend fun acquire(title: String): Long? {
        if (!enabled) return null
        stopCurrent()
        this.title = title
        handle?.takeIf { active }?.let { return it }
        val deferred = CompletableDeferred<Long>()
        waiting = deferred
        active = true
        window?.let { if (it.isMinimized) it.isMinimized = false }
        val id = withTimeoutOrNull(5_000) { deferred.await() }
        if (id == null) {
            Logger.info("Zone vidéo indisponible : mpv s'ouvre dans sa propre fenêtre")
            close()
        }
        return id
    }

    /** Called by the video area once its native window exists. */
    fun attached(id: Long) {
        handle = id
        waiting?.complete(id)
    }

    fun detached() {
        handle = null
    }

    /** Follows the mpv drawing in the area: full screen and picture in picture asked from its controls, and its exit. */
    fun follow(process: Process, ipcPath: String?) {
        this.process = process
        this.ipcPath = ipcPath
        // "Plein écran" in the settings: the window goes full screen with the video.
        if (Store.settings.fullscreen && !fullscreen && !pip) switchFullscreen(true)
        if (ipcPath != null) scope.launch {
            MpvIpc.watch(
                ipcPath, process, listOf("fullscreen"),
                onEvent = { name, args ->
                    if (name == "client-message" && args.firstOrNull() == "cspc-pip") switchPip(pip = !pip)
                    // mpv is quitting: if a script holds it up, it is closed anyway after a moment.
                    if (name == "shutdown") scope.launch {
                        if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) process.destroy()
                    }
                },
            ) { name, value ->
                if (name == "fullscreen" && process === this@PlayerSurface.process) switchFullscreen(value.asBoolean())
            }
        }
        scope.launch {
            process.waitFor()
            if (this@PlayerSurface.process === process) {
                this@PlayerSurface.process = null
                this@PlayerSurface.ipcPath = null
                close()
            }
        }
    }

    /** "Retour" above the video: stops it. */
    fun stop() {
        scope.launch { stopCurrent() }
    }

    private fun stopCurrent() {
        val current = process ?: return
        process = null
        val path = ipcPath
        ipcPath = null
        // A clean quit (exit code 0) is not taken for a playback failure.
        if (path != null && current.isAlive) MpvIpc.send(path, listOf("quit", 0))
        if (!current.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) current.destroy()
    }

    private fun close() {
        if (pip || fullscreen) restoreWindow()
        pip = false
        fullscreen = false
        active = false
        waiting = null
    }

    fun switchFullscreen(on: Boolean) {
        if (on == fullscreen) return
        val state = window ?: return
        if (on) {
            if (pip) restoreWindow().also { pip = false }
            saveWindow(state)
            state.placement = WindowPlacement.Fullscreen
        } else {
            restoreWindow()
        }
        fullscreen = on
        // Keeps mpv's own button (uosc) in step when the change comes from the app.
        ipcPath?.let { path -> scope.launch { MpvIpc.send(path, listOf("set_property", "fullscreen", on)) } }
    }

    /** Picture in picture: a small player above the other windows, in the bottom-right corner. */
    fun switchPip(pip: Boolean) {
        if (pip == this.pip) return
        val state = window ?: return
        if (pip) {
            if (fullscreen) {
                restoreWindow()
                fullscreen = false
                ipcPath?.let { path -> scope.launch { MpvIpc.send(path, listOf("set_property", "fullscreen", false)) } }
            }
            saveWindow(state)
            val area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val size = DpSize(480.dp, 300.dp)
            state.placement = WindowPlacement.Floating
            state.size = size
            state.position = WindowPosition((area.x + area.width - 480 - 24).dp, (area.y + area.height - 300 - 24).dp)
        } else {
            restoreWindow()
        }
        this.pip = pip
    }

    private fun saveWindow(state: WindowState) {
        if (saved == null) saved = Triple(state.placement, state.size, state.position)
    }

    private fun restoreWindow() {
        val state = window ?: return
        val (placement, size, position) = saved ?: return
        saved = null
        state.placement = WindowPlacement.Floating
        state.size = size
        state.position = position
        state.placement = placement
    }

    /** A key pressed while the video area has the focus, passed on to mpv (`keypress`). */
    fun key(name: String) {
        val path = ipcPath ?: return
        keys.execute { MpvIpc.send(path, listOf("keypress", name)) }
    }

    /** mpv's name for an AWT key (null for keys it doesn't need). */
    fun mpvKey(code: Int, char: Char, ctrl: Boolean, alt: Boolean, shift: Boolean): String? {
        val special = when (code) {
            java.awt.event.KeyEvent.VK_SPACE -> "SPACE"
            java.awt.event.KeyEvent.VK_LEFT -> "LEFT"
            java.awt.event.KeyEvent.VK_RIGHT -> "RIGHT"
            java.awt.event.KeyEvent.VK_UP -> "UP"
            java.awt.event.KeyEvent.VK_DOWN -> "DOWN"
            java.awt.event.KeyEvent.VK_ENTER -> "ENTER"
            java.awt.event.KeyEvent.VK_ESCAPE -> "ESC"
            java.awt.event.KeyEvent.VK_PAGE_UP -> "PGUP"
            java.awt.event.KeyEvent.VK_PAGE_DOWN -> "PGDWN"
            java.awt.event.KeyEvent.VK_HOME -> "HOME"
            java.awt.event.KeyEvent.VK_END -> "END"
            java.awt.event.KeyEvent.VK_TAB -> "TAB"
            java.awt.event.KeyEvent.VK_BACK_SPACE -> "BS"
            java.awt.event.KeyEvent.VK_DELETE -> "DEL"
            in java.awt.event.KeyEvent.VK_F1..java.awt.event.KeyEvent.VK_F12 -> "F${code - java.awt.event.KeyEvent.VK_F1 + 1}"
            else -> null
        }
        val letter = if ((ctrl || alt) && (code in java.awt.event.KeyEvent.VK_A..java.awt.event.KeyEvent.VK_Z || code in java.awt.event.KeyEvent.VK_0..java.awt.event.KeyEvent.VK_9)) {
            code.toChar().lowercase()
        } else null
        val base = special ?: letter ?: char.takeIf { !it.isISOControl() && it != java.awt.event.KeyEvent.CHAR_UNDEFINED }?.toString() ?: return null
        val prefix = buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            // Shift is already in the character for letters and symbols.
            if (shift && special != null) append("Shift+")
        }
        return prefix + base
    }
}
