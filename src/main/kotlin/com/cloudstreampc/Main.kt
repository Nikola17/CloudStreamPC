package com.cloudstreampc

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cloudstreampc.core.Backup
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.net.Network
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.providers.BuiltinProviders
import com.cloudstreampc.ui.CloudStreamTheme
import com.cloudstreampc.ui.StartupGate
import com.cloudstreampc.ui.defaultRepository
import com.cloudstreampc.ui.rememberNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun main() {
    Logger.install()
    Network.apply(Store.settings.dns)

    if (!Store.settings.firstLaunchDone && Store.settings.repositories.isEmpty()) {
        Store.updateSettings { it.copy(repositories = listOf(defaultRepository)) }
    }

    appScope.launch {
        launch { BuiltinProviders.sync() }
        PluginManager.loadInstalled()
        if (Store.settings.autoUpdatePlugins) {
            runCatching { PluginManager.updateAll() }.onFailure { Logger.error("Mise à jour des extensions", it) }
        }
        Backup.autoBackupIfDue()
    }

    application {
        val state = rememberWindowState(width = 1360.dp, height = 860.dp, placement = WindowPlacement.Maximized)
        val nav = rememberNavigator()
        var confirmExit by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = { if (Store.settings.confirmExit) confirmExit = true else exitApplication() },
            state = state,
            title = "CloudStream",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { event ->
                val back = event.key == Key.Escape || (event.isAltPressed && event.key == Key.DirectionLeft)
                if (back && event.type == KeyEventType.KeyDown) nav.back() else false
            },
        ) {
            // Mouse "back" button (button 4) navigates back, like in a browser.
            DisposableEffect(Unit) {
                val listener = AWTEventListener { e ->
                    if (e is MouseEvent && e.id == MouseEvent.MOUSE_RELEASED && e.button == 4) nav.back()
                }
                Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
            }
            StartupGate(nav)
            if (confirmExit) {
                CloudStreamTheme {
                    AlertDialog(
                        onDismissRequest = { confirmExit = false },
                        title = { Text("Quitter CloudStreamPC ?") },
                        confirmButton = { TextButton(onClick = ::exitApplication) { Text("Quitter") } },
                        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Annuler") } },
                    )
                }
            }
        }
    }
}
