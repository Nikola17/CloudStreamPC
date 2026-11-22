package com.cloudstreampc

import androidx.compose.material3.AlertDialog
import com.cloudstreampc.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.cloudstreampc.core.Backup
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.net.Network
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.providers.BuiltinProviders
import com.cloudstreampc.ui.CloudStreamTheme
import com.cloudstreampc.ui.StartupGate
import com.cloudstreampc.ui.rememberNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Mouse "back" button as Java numbers it: on Linux, 4 and 5 are the horizontal scroll of touchpads. */
private val backButton = if (System.getProperty("os.name").lowercase().contains("linux")) 6 else 4

/** Ensures only one MonCloudi instance runs at a time; a second launch activates the existing window. */
private object SingleInstance {
    @Volatile var onActivate: (() -> Unit)? = null
    private var server: ServerSocket? = null
    private val portFile get() = java.io.File(com.cloudstreampc.core.AppDirs.root, ".instance-port")

    /** Returns false if another instance is already running (and was just signaled to come to front). */
    fun acquire(): Boolean {
        val existingPort = runCatching { portFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() }.getOrNull()
        if (existingPort != null && existingPort in 1..65535) {
            val notified = runCatching {
                Socket(InetAddress.getLoopbackAddress(), existingPort).use { sock ->
                    sock.soTimeout = 1000
                    sock.getOutputStream().write("SHOW\n".toByteArray())
                    sock.getOutputStream().flush()
                }
                true
            }.getOrDefault(false)
            if (notified) return false
        }
        val bound = runCatching { ServerSocket(0, 4, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return true
        server = bound
        runCatching { portFile.writeText(bound.localPort.toString()); portFile.deleteOnExit() }
        Thread({
            while (!bound.isClosed) {
                runCatching {
                    bound.accept().use { client ->
                        val line = client.getInputStream().bufferedReader().readLine()
                        if (line == "SHOW") onActivate?.invoke()
                    }
                }
            }
        }, "moncloudi-instance").apply { isDaemon = true }.start()
        return true
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { portFile.delete() }
    }
}

fun main(args: Array<String>) {
    val startHidden = com.cloudstreampc.core.Autostart.HIDDEN_ARG in args
    val smokeDir = args.firstOrNull { it.startsWith("--smoke-test=") }?.substringAfter("=")?.let { java.io.File(it) }
    if (smokeDir == null && !SingleInstance.acquire()) {
        return
    }
    Logger.install()
    com.cloudstreampc.core.CrashReport.install()
    com.cloudstreampc.core.CrashReport.collectJvmCrashes()
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { SingleInstance.release() }
        runCatching { com.cloudstreampc.net.ChromiumBrowser.shutdown() }
    })
    Network.apply(Network.chosen())
    com.cloudstreampc.plugins.PluginManager.configureLibrary()

    // Objects holding Compose state are created here, before any background thread can create them
    // while the window is composing (Compose refuses to read a state born after its snapshot).
    listOf(
        com.cloudstreampc.player.PlaybackManager, com.cloudstreampc.cast.CastManager, com.cloudstreampc.core.Taste, com.cloudstreampc.ui.ImageLoader,
        com.cloudstreampc.plugins.ExtensionHealth, com.cloudstreampc.player.PlayerSurface, com.cloudstreampc.core.Parental,
    )

    appScope.launch {
        launch { BuiltinProviders.sync() }
        PluginManager.loadInstalled()
        if (Store.settings.autoUpdatePlugins) {
            runCatching { PluginManager.updateAll() }.onFailure { Logger.error("Mise à jour des extensions", it) }
        }
        com.cloudstreampc.core.EpisodeWatcher.start()
        com.cloudstreampc.player.RemoteControl.applySettings()
        com.cloudstreampc.plugins.ExtensionHealth.checkIfDue()
        com.cloudstreampc.sync.FolderSync.start()
        com.cloudstreampc.ui.GamepadInput.start()
        Backup.autoBackupIfDue()
        if (Store.settings.recommendationsEnabled) com.cloudstreampc.providers.TasteEnricher.enrichSoon()
    }

    application {
        val state = rememberWindowState(width = 1360.dp, height = 860.dp, placement = WindowPlacement.Maximized)
        com.cloudstreampc.player.PlayerSurface.window = state
        val nav = rememberNavigator()
        var confirmExit by remember { mutableStateOf(false) }

        // Desktop notifications for new episodes, through the system tray.
        val trayState = rememberTrayState()
        DisposableEffect(Unit) {
            com.cloudstreampc.core.EpisodeWatcher.onNewEpisode = { release ->
                trayState.sendNotification(
                    Notification(com.cloudstreampc.ui.I18n.tr("Nouvel épisode disponible"), "${release.item.name} · ${release.label}", Notification.Type.Info),
                )
            }
            onDispose { com.cloudstreampc.core.EpisodeWatcher.onNewEpisode = null }
        }
        // Hidden in the notification area: started with the computer, or window closed with "keep running".
        var windowVisible by remember { mutableStateOf(!(startHidden && isTraySupported)) }
        fun show() {
            windowVisible = true
            state.isMinimized = false
        }
        DisposableEffect(Unit) {
            SingleInstance.onActivate = { javax.swing.SwingUtilities.invokeLater(::show) }
            onDispose { SingleInstance.onActivate = null }
        }
        val inTray = isTraySupported && (Store.settings.newEpisodeNotifications || Store.settings.closeToTray || !windowVisible)
        if (inTray) {
            Tray(
                icon = painterResource("icon.png"),
                state = trayState,
                tooltip = "MonCloudi",
                onAction = {
                    show()
                    if (com.cloudstreampc.core.Store.library.newEpisodes.isNotEmpty()) nav.select(com.cloudstreampc.ui.Tab.Library)
                },
                menu = {
                    Item(com.cloudstreampc.ui.I18n.tr("Ouvrir MonCloudi"), onClick = ::show)
                    Item(com.cloudstreampc.ui.I18n.tr("Quitter"), onClick = ::exitApplication)
                },
            )
        }
        // A crash of the window: report saved, offered to the user, then the app closes cleanly.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides
                androidx.compose.ui.window.WindowExceptionHandlerFactory { window ->
                    androidx.compose.ui.window.WindowExceptionHandler { error ->
                        Logger.error("Plantage de la fenêtre", error)
                        showCrashDialog(window, error)
                        window.dispose()
                        kotlin.system.exitProcess(1)
                    }
                },
        ) {
        Window(
            visible = windowVisible,
            onCloseRequest = {
                when {
                    Store.settings.closeToTray && isTraySupported -> {
                        windowVisible = false
                        if (!Store.settings.backgroundHintShown) {
                            Store.updateSettings { it.copy(backgroundHintShown = true) }
                            trayState.sendNotification(
                                Notification(
                                    "MonCloudi",
                                    com.cloudstreampc.ui.I18n.tr("MonCloudi continue en arrière-plan. Pour quitter : clic droit sur son icône → Quitter."),
                                    Notification.Type.Info,
                                ),
                            )
                        }
                    }
                    Store.settings.confirmExit -> confirmExit = true
                    else -> exitApplication()
                }
            },
            state = state,
            // Picture in picture: the small player stays above the other windows.
            alwaysOnTop = com.cloudstreampc.player.PlayerSurface.pip,
            title = "MonCloudi",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { event ->
                val isEsc = event.key == Key.Escape
                val isBack = isEsc || (event.isAltPressed && event.key == Key.DirectionLeft)
                val isBackspace = event.key == Key.Backspace
                val surface = com.cloudstreampc.player.PlayerSurface
                when {
                    event.type != KeyEventType.KeyDown -> false
                    surface.active && isBackspace -> true.also { surface.stop() }
                    !isBack -> false
                    // Escape leaves full screen / picture in picture first, then closes the video cleanly.
                    surface.fullscreen -> true.also { surface.switchFullscreen(false) }
                    surface.pip -> true.also { surface.switchPip(false) }
                    surface.active -> true.also { surface.stop() }
                    else -> nav.back()
                }
            },
        ) {
            // Mouse "back" button (button 4) navigates back, like in a browser.
            DisposableEffect(Unit) {
                val listener = AWTEventListener { e ->
                    if (e is MouseEvent && e.id == MouseEvent.MOUSE_RELEASED && e.button == backButton && !com.cloudstreampc.player.PlayerSurface.active) nav.back()
                }
                Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
            }
            StartupGate(nav)
            if (smokeDir != null) com.cloudstreampc.ui.SmokeTest(window, nav, smokeDir) { exitApplication() }
            if (confirmExit) {
                CloudStreamTheme {
                    AlertDialog(
                        onDismissRequest = { confirmExit = false },
                        title = { Text("Quitter MonCloudi ?") },
                        confirmButton = { TextButton(onClick = ::exitApplication) { Text("Quitter") } },
                        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Annuler") } },
                    )
                }
            }
        }
        }
    }
}

/** Swing dialog (Compose may be broken at this point): report saved, copied or posted on GitHub. */
private fun showCrashDialog(parent: java.awt.Window?, error: Throwable) {
    val tr = com.cloudstreampc.ui.I18n::tr
    val report = com.cloudstreampc.core.CrashReport.build(com.cloudstreampc.ui.APP_VERSION, error)
    val file = runCatching { com.cloudstreampc.core.CrashReport.save(report) }.getOrNull()
    val options = arrayOf(tr("Signaler sur GitHub"), tr("Copier le rapport"), tr("Fermer"))
    val message = tr("MonCloudi a rencontré un problème et doit se fermer.") + "\n" +
        (file?.let { tr("Rapport enregistré dans {}").replace("{}", it.path) + "\n" } ?: "") +
        tr("Le rapport ne contient ni mots de passe ni clés.")
    val choice = javax.swing.JOptionPane.showOptionDialog(
        parent, message, "MonCloudi", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.ERROR_MESSAGE,
        null, options, options[0],
    )
    when (choice) {
        0 -> runCatching {
            val title = "Plantage : ${error.javaClass.simpleName}: ${error.message.orEmpty().take(80)}"
            java.awt.Desktop.getDesktop().browse(java.net.URI(com.cloudstreampc.core.CrashReport.issueUrl(title, report)))
        }
        1 -> runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(report), null)
            javax.swing.JOptionPane.showMessageDialog(parent, tr("Rapport copié"), "MonCloudi", javax.swing.JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
