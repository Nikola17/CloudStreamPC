package com.cloudstreampc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * New version available: "Installer maintenant" downloads the installer for this system, checks its
 * SHA-256, opens it and quits so it can replace the app.
 */
@Composable
fun UpdateDialog(release: Updates.Release, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("CloudStreamPC ${release.version} est disponible") },
        text = {
            Column {
                Text(
                    release.notes?.take(1200) ?: "Une nouvelle version est disponible.", fontSize = 13.sp,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
                progress?.let {
                    Text("Téléchargement · ${(it * 100).toInt()} %", fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = CsColors.Error, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            if (release.installer != null) {
                Button(enabled = progress == null, onClick = {
                    error = null
                    progress = 0f
                    scope.launch {
                        runCatching {
                            val file = withContext(Dispatchers.IO) { Updates.download(release) { p -> progress = p } }
                            Updates.launch(file)
                            delay(800)
                            exitProcess(0)
                        }.onFailure {
                            Logger.error("Mise à jour", it)
                            progress = null
                            error = I18n.tr("Échec : {}").replace("{}", it.message ?: it.javaClass.simpleName)
                        }
                    }
                }) { Text("Installer maintenant") }
            } else {
                Button(onClick = { openInBrowser(release.url); onDismiss() }) { Text("Télécharger") }
            }
        },
        dismissButton = {
            if (progress == null) {
                if (release.installer != null) TextButton(onClick = { openInBrowser(release.url) }) { Text("Page de la version") }
                TextButton(onClick = onDismiss) { Text("Plus tard") }
            }
        },
    )
}
