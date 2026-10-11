package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import com.cloudstreampc.download.DownloadManager
import com.cloudstreampc.net.Network
import com.cloudstreampc.player.ExternalPlayer
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.swing.JFileChooser

private fun chooseFolder(title: String): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

@Composable
fun GeneralSettings() {
    val s = Store.settings
    var legal by remember { mutableStateOf(false) }
    SettingsCategory("Général")
    SettingsItem("Langue de l'application", "Français")
    SettingsItem("Mention légale", "CloudStreamPC n'héberge aucun contenu") { legal = true }

    SettingsCategory("Téléchargements")
    SettingsItem("Dossier de téléchargement", DownloadManager.folder.path) {
        chooseFolder("Dossier de téléchargement")?.let { dir -> Store.updateSettings { it.copy(downloadDir = dir) } }
    }
    SettingsChoice("Téléchargements simultanés", (1..5).map { it to "$it" }, s.parallelDownloads) { v ->
        Store.updateSettings { it.copy(parallelDownloads = v) }
        DownloadManager.applyParallelSetting()
    }

    SettingsCategory("Contournement FAI")
    SettingsChoice("DNS over HTTPS", Network.providers.map { it.id to it.label }, s.dns) { v ->
        Store.updateSettings { it.copy(dns = v) }
        Network.apply(v)
    }

    SettingsCategory("Liens")
    SettingsItem("CloudStreamPC sur GitHub", "github.com/Nikola17/CloudStreamPC") { openInBrowser("https://github.com/Nikola17/CloudStreamPC") }
    SettingsItem("CloudStream sur GitHub", "github.com/recloudstream/cloudstream") { openInBrowser("https://github.com/recloudstream/cloudstream") }
    SettingsItem("Wiki CloudStream", "Créer et utiliser des extensions") { openInBrowser("https://recloudstream.github.io/csdocs/") }

    if (legal) {
        InfoDialog("Mention légale", { legal = false }) {
            Text(
                "CloudStreamPC n'héberge, ne stocke et ne distribue aucun contenu. Il affiche ce que renvoient les " +
                    "extensions, comptes IPTV et addons que vous ajoutez vous-même ; vous êtes responsable de leur usage " +
                    "et du respect des lois de votre pays.",
            )
        }
    }
}

@Composable
fun PlayerSettings() {
    val s = Store.settings
    SettingsCategory("Par défaut")
    SettingsChoice(
        "Qualité préférée",
        listOf(null to "La meilleure", 2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p"),
        s.preferredQuality,
    ) { v -> Store.updateSettings { it.copy(preferredQuality = v) } }
    SettingsChoice(
        "Lecteur vidéo préféré",
        listOf(PlayerKind.MPV to "mpv (inclus)", PlayerKind.VLC to "VLC", PlayerKind.CUSTOM to "Autre lecteur"),
        s.player,
    ) { v -> Store.updateSettings { it.copy(player = v) } }
    val detected = ExternalPlayer.findPlayer(s.player, s.playerPath)
    SettingsText(
        "Chemin du lecteur",
        s.playerPath.orEmpty(),
        placeholder = "Détection automatique",
        subtitle = detected?.absolutePath ?: "Introuvable : indiquez le chemin de l'exécutable",
    ) { v -> Store.updateSettings { it.copy(playerPath = v.ifBlank { null }) } }

    SettingsCategory("Fonctionnalités du lecteur")
    SettingsSwitch("Lancer directement la meilleure source", "Lecture immédiate dans la meilleure qualité, sans choisir", s.autoPlayBestSource) { v ->
        Store.updateSettings { it.copy(autoPlayBestSource = v) }
    }
    SettingsSwitch("Lecture automatique de l'épisode suivant", null, s.autoPlayNextEpisode) { v -> Store.updateSettings { it.copy(autoPlayNextEpisode = v) } }
    SettingsSwitch("Mettre à jour la progression", "Reprendre là où vous vous êtes arrêté", s.resumePlayback) { v -> Store.updateSettings { it.copy(resumePlayback = v) } }
    SettingsSwitch("Plein écran", null, s.fullscreen) { v -> Store.updateSettings { it.copy(fullscreen = v) } }
    SettingsChoice(
        "Vitesse de lecture",
        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).map { it to "${it}x".replace(".0x", "x") },
        s.playbackSpeed,
    ) { v -> Store.updateSettings { it.copy(playbackSpeed = v) } }

    SettingsCategory("Sous-titres et audio")
    SettingsChoice("Taille des sous-titres", listOf(42 to "Petite", 55 to "Normale", 68 to "Grande", 82 to "Très grande"), s.subtitleSize) { v ->
        Store.updateSettings { it.copy(subtitleSize = v) }
    }
    SettingsText("Langues des sous-titres", s.subtitleLanguages, "fr,en") { v -> Store.updateSettings { it.copy(subtitleLanguages = v) } }
    SettingsText("Langues audio", s.audioLanguages, "fr,en") { v -> Store.updateSettings { it.copy(audioLanguages = v) } }

    SettingsCategory("Cache")
    val nav = LocalNavigator.current
    SettingsItem("Vider le cache vidéo et images", "Libère la mémoire utilisée par les affiches et les fiches") {
        ImageLoader.clear()
        DetailsCache.clear()
        nav.toast("Cache vidé")
    }
}

@Composable
fun ProviderSettings() {
    val s = Store.settings
    var testing by remember { mutableStateOf(false) }
    val languages = PluginManager.providers.map { it.lang }.filter { it.isNotBlank() }.distinct().sorted()
    SettingsCategory("Fournisseurs")
    SettingsMultiChoice("Langues des extensions", languages.map { it to it.uppercase() }, s.providerLanguages, "Toutes") { v ->
        Store.updateSettings { it.copy(providerLanguages = v) }
    }
    SettingsMultiChoice(
        "Média préféré",
        listOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Cartoon, TvType.Documentary, TvType.AsianDrama, TvType.Live)
            .map { it.name to typeName(it) },
        s.preferredMedia,
    ) { v -> Store.updateSettings { it.copy(preferredMedia = v) } }
    SettingsSwitch("Afficher doublé / sous-titré", "Badge VF / VOSTFR sur les animés", s.showDubSubBadge) { v -> Store.updateSettings { it.copy(showDubSubBadge = v) } }
    SettingsSwitch("Activer le contenu NSFW", "Sur les extensions qui en proposent", s.nsfw) { v -> Store.updateSettings { it.copy(nsfw = v) } }
    SettingsItem("Tester toutes les extensions", "Lance une recherche sur chaque fournisseur") { testing = true }
    if (testing) ProviderTestDialog { testing = false }
}

fun typeName(type: TvType) = when (type) {
    TvType.Movie -> "Films"
    TvType.TvSeries -> "Séries"
    TvType.Anime -> "Animés"
    TvType.AnimeMovie -> "Films d'animation"
    TvType.Cartoon -> "Dessins animés"
    TvType.Documentary -> "Documentaires"
    TvType.AsianDrama -> "Drama asiatiques"
    TvType.Live -> "TV en direct"
    else -> type.name
}

@Composable
private fun ProviderTestDialog(onDismiss: () -> Unit) {
    val results = remember { mutableStateMapOf<String, String?>() }
    val scope = rememberCoroutineScope()
    val providers = remember { PluginManager.providers }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        providers.forEach { api ->
            results[api.name] = null
            scope.launch {
                val start = System.currentTimeMillis()
                results[api.name] = try {
                    val count = withTimeout(30_000) { withContext(Dispatchers.IO) { api.search("the", 1)?.items?.size ?: 0 } }
                    "✓ $count résultats en ${(System.currentTimeMillis() - start) / 100 / 10.0} s"
                } catch (t: Throwable) {
                    "✗ ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }
    InfoDialog("Test des extensions", onDismiss) {
        providers.forEach { api ->
            val result = results[api.name]
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    result == null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    result.startsWith("✓") -> Icon(Icons.Default.Check, null, tint = CsColors.Good)
                    else -> Icon(Icons.Default.Close, null, tint = CsColors.Error)
                }
                Text("  ${api.name}", modifier = Modifier.width(220.dp))
                Text(result?.drop(2) ?: "…", color = CsColors.TextMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutSettings() {
    val s = Store.settings
    SettingsCategory("Apparence")
    SettingsItem("Couleur principale", CsColors.primaryChoices.firstOrNull { it.first == s.primaryColor }?.second ?: "Personnalisée")
    FlowRow(Modifier.padding(start = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CsColors.primaryChoices.forEach { (color, label) ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(color))
                    .border(3.dp, if (color == s.primaryColor) Color.White else Color.Transparent, CircleShape)
                    .clickable { Store.updateSettings { it.copy(primaryColor = color) } },
            )
        }
    }
    SettingsChoice("Thème", listOf("black" to "Noir (AMOLED)", "dark" to "Sombre", "gray" to "Gris"), s.theme) { v ->
        Store.updateSettings { it.copy(theme = v) }
    }
    SettingsChoice("Taille des affiches", listOf(0 to "Petite", 1 to "Normale", 2 to "Grande"), s.posterSize) { v ->
        Store.updateSettings { it.copy(posterSize = v) }
    }
    SettingsChoice("Position du titre", listOf(true to "Sous l'affiche", false to "Sur l'affiche"), s.titleBelowPoster) { v ->
        Store.updateSettings { it.copy(titleBelowPoster = v) }
    }
    SettingsSwitch("Badge de qualité sur les affiches", "HD, 4K, CAM…", s.showQualityBadge) { v -> Store.updateSettings { it.copy(showQualityBadge = v) } }
    SettingsSwitch("Note sur les affiches", null, s.showScoreBadge) { v -> Store.updateSettings { it.copy(showScoreBadge = v) } }

    SettingsCategory("Fonctionnalités")
    SettingsSwitch("Bannière en haut de l'accueil", null, s.showHero) { v -> Store.updateSettings { it.copy(showHero = v) } }
    SettingsSwitch("Afficher la distribution", "Sur la fiche des films et séries", s.showCast) { v -> Store.updateSettings { it.copy(showCast = v) } }
    SettingsSwitch("Bouton aléatoire", "Ouvre un titre au hasard depuis l'accueil", s.randomButton) { v -> Store.updateSettings { it.copy(randomButton = v) } }
    SettingsSwitch("Afficher l'horloge", null, s.showClock) { v -> Store.updateSettings { it.copy(showClock = v) } }
    SettingsSwitch("Historique de recherche", null, s.searchHistoryEnabled) { v -> Store.updateSettings { it.copy(searchHistoryEnabled = v) } }
    SettingsSwitch("Confirmer avant de quitter", null, s.confirmExit) { v -> Store.updateSettings { it.copy(confirmExit = v) } }
}

@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    val text = remember {
        runCatching { com.cloudstreampc.core.Logger.file.readLines().takeLast(400).joinToString("\n") }.getOrDefault("Journal vide")
    }
    InfoDialog("Journaux", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            }) { Text("Copier") }
            OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(com.cloudstreampc.core.AppDirs.logs) } }) { Text("Ouvrir le dossier") }
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = 8.dp).size(width = 620.dp, height = 420.dp).background(Color.Black)) {
            item {
                SelectionContainer {
                    Text(text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
