package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.download.DownloadItem
import com.cloudstreampc.download.DownloadManager
import com.cloudstreampc.download.DownloadStatus
import com.cloudstreampc.player.ExternalPlayer
import java.awt.Desktop

@Composable
fun DownloadsScreen() {
    val nav = LocalNavigator.current
    val items = DownloadManager.items
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Téléchargements") {
            TextButton(onClick = { runCatching { Desktop.getDesktop().open(DownloadManager.folder) } }) {
                Icon(AppIcons.FolderOpen, null)
                Text("  Ouvrir le dossier")
            }
        }
        val done = items.filter { it.status == DownloadStatus.DONE }
        if (items.isNotEmpty()) {
            Text(
                "${items.size} téléchargement(s) · ${formatBytes(done.sumOf { it.file.length() })} sur le disque · ${DownloadManager.folder.path}",
                color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        if (items.isEmpty()) {
            MessageBox(
                "Aucun téléchargement",
                "Sur la fiche d'un film ou d'un épisode, cliquez sur l'icône de téléchargement pour le regarder plus tard sans connexion.",
            )
            return
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                DownloadRow(item, onPlay = {
                    runCatching { ExternalPlayer.playFile(item.file, listOfNotNull(item.title, item.episodeLabel).joinToString(" · ")) }
                        .onFailure { nav.toast(it.message ?: "Impossible de lancer le lecteur") }
                })
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CsColors.Surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(8.dp)).background(CsColors.SurfaceHigh)) {
            RemoteImage(item.posterUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.episodeLabel?.let { Text(it, color = CsColors.TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val status = when (item.status) {
                DownloadStatus.QUEUED -> "En attente"
                DownloadStatus.RUNNING -> "Téléchargement · ${(item.progress * 100).toInt()} %"
                DownloadStatus.PAUSED -> "En pause · ${(item.progress * 100).toInt()} %"
                DownloadStatus.DONE -> "Terminé"
                DownloadStatus.FAILED -> "Échec : ${item.error ?: "erreur inconnue"}"
            }
            val size = if (item.status == DownloadStatus.DONE) item.file.length() else item.downloadedBytes
            Text(
                "$status · ${formatBytes(size)}" + if (item.totalBytes > 0 && item.status != DownloadStatus.DONE) " / ${formatBytes(item.totalBytes)}" else "",
                color = if (item.status == DownloadStatus.FAILED) CsColors.Error else CsColors.TextMuted,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (item.status != DownloadStatus.DONE) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        when (item.status) {
            DownloadStatus.DONE -> IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Lire", tint = CsColors.Primary, modifier = Modifier.size(30.dp)) }
            DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                IconButton(onClick = { DownloadManager.pause(item.id) }) { Icon(AppIcons.Pause, "Pause") }
            DownloadStatus.PAUSED -> IconButton(onClick = { DownloadManager.start(item.id) }) { Icon(AppIcons.Download, "Reprendre") }
            DownloadStatus.FAILED -> IconButton(onClick = { DownloadManager.start(item.id) }) { Icon(Icons.Default.Refresh, "Réessayer") }
        }
        IconButton(onClick = { DownloadManager.delete(item.id) }) { Icon(Icons.Default.Delete, "Supprimer", tint = CsColors.TextMuted) }
    }
}
