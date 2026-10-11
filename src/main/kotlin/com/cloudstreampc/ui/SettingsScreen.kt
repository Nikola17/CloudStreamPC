package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.ExternalPlayer
import java.awt.Desktop
import javax.swing.JFileChooser

const val APP_VERSION = "1.0.0"

@Composable
fun SettingsScreen() {
    val settings = Store.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Paramètres")
        Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Lecteur vidéo") {
                val options = listOf(
                    PlayerKind.MPV to "mpv (recommandé, inclus avec l'installateur Windows)",
                    PlayerKind.VLC to "VLC",
                    PlayerKind.CUSTOM to "Autre lecteur (chemin personnalisé)",
                )
                options.forEach { (kind, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { Store.updateSettings { it.copy(player = kind) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(settings.player == kind, onClick = { Store.updateSettings { it.copy(player = kind) } })
                        Text(label)
                    }
                }
                val detected = ExternalPlayer.findPlayer(settings.player, settings.playerPath)
                Text(
                    detected?.let { "Lecteur utilisé : ${it.absolutePath}" } ?: "Lecteur introuvable : installez-le ou indiquez son chemin.",
                    color = if (detected != null) CsColors.Good else CsColors.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                var path by remember { mutableStateOf(settings.playerPath.orEmpty()) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        path,
                        {
                            path = it
                            Store.updateSettings { s -> s.copy(playerPath = it.ifBlank { null }) }
                        },
                        label = { Text("Chemin du lecteur (facultatif)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {
                        val chooser = JFileChooser().apply { dialogTitle = "Choisir le lecteur vidéo" }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            path = chooser.selectedFile.absolutePath
                            Store.updateSettings { s -> s.copy(playerPath = path) }
                        }
                    }) { Text("Parcourir…") }
                }
            }

            Section("Extensions") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mettre à jour automatiquement les extensions")
                        Text("Au démarrage, comme l'application Android.", color = CsColors.TextMuted, fontSize = 13.sp)
                    }
                    Switch(settings.autoUpdatePlugins, { v -> Store.updateSettings { it.copy(autoUpdatePlugins = v) } })
                }
            }

            Section("Données") {
                Text("Favoris, historique, extensions et journaux sont enregistrés dans :", color = CsColors.TextMuted, fontSize = 13.sp)
                Text(AppDirs.root.absolutePath, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.root) } }) { Text("Ouvrir le dossier") }
                    OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.logs) } }) { Text("Ouvrir les journaux") }
                }
            }

            Section("À propos") {
                Text("CloudStreamPC $APP_VERSION", fontWeight = FontWeight.SemiBold)
                Text(
                    "Version Windows de CloudStream. Utilise la bibliothèque officielle de CloudStream " +
                        "et charge les mêmes extensions (.cs3) que l'application Android.",
                    color = CsColors.TextMuted, fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CsColors.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}
