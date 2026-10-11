package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
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

const val APP_VERSION = "1.1.0"

@OptIn(ExperimentalLayoutApi::class)
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

            Section("Lecture") {
                SwitchRow("Reprendre là où je me suis arrêté", "La position est enregistrée pendant la lecture dans mpv.", settings.resumePlayback) { v ->
                    Store.updateSettings { it.copy(resumePlayback = v) }
                }
                SwitchRow("Lancer automatiquement l'épisode suivant", "Proposé à la fin d'un épisode, avec un compte à rebours de 10 s.", settings.autoPlayNextEpisode) { v ->
                    Store.updateSettings { it.copy(autoPlayNextEpisode = v) }
                }
                SwitchRow("Lancer directement la meilleure source", "Sans passer par la liste des sources.", settings.autoPlayBestSource) { v ->
                    Store.updateSettings { it.copy(autoPlayBestSource = v) }
                }
                SwitchRow("Plein écran", null, settings.fullscreen) { v -> Store.updateSettings { it.copy(fullscreen = v) } }

                Text("Qualité préférée", modifier = Modifier.padding(top = 12.dp))
                Text("Utilisée pour choisir la source recommandée.", color = CsColors.TextMuted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(null to "La meilleure", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
                        .forEach { (value, label) ->
                            FilterChip(settings.preferredQuality == value, { Store.updateSettings { it.copy(preferredQuality = value) } }, { Text(label) })
                        }
                }

                Text("Taille des sous-titres", modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(42 to "Petite", 55 to "Normale", 68 to "Grande", 82 to "Très grande").forEach { (value, label) ->
                        FilterChip(settings.subtitleSize == value, { Store.updateSettings { it.copy(subtitleSize = value) } }, { Text(label) })
                    }
                }

                var subLangs by remember { mutableStateOf(settings.subtitleLanguages) }
                var audioLangs by remember { mutableStateOf(settings.audioLanguages) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        subLangs,
                        { subLangs = it; Store.updateSettings { s -> s.copy(subtitleLanguages = it.trim()) } },
                        label = { Text("Langues des sous-titres") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        audioLangs,
                        { audioLangs = it; Store.updateSettings { s -> s.copy(audioLanguages = it.trim()) } },
                        label = { Text("Langues audio") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Codes séparés par des virgules, par ordre de préférence (ex. fr,fre,en). Utilisé par mpv.",
                    color = CsColors.TextMuted, fontSize = 12.sp,
                )
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
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, color = CsColors.TextMuted, fontSize = 13.sp)
        }
        Switch(checked, onChange)
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
