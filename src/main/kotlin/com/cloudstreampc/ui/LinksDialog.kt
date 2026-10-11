package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.ExternalPlayer
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Fetches the sources of a movie/episode and lets the user pick one, like the app's source picker. */
@Composable
fun LinksDialog(api: MainAPI, response: LoadResponse, target: Playable, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val links = remember(target) { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember(target) { mutableStateListOf<SubtitleFile>() }
    var loading by remember(target) { mutableStateOf(true) }
    var error by remember(target) { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        try {
            withContext(Dispatchers.IO) {
                api.loadLinks(
                    target.data,
                    false,
                    { sub -> if (subtitles.none { it.url == sub.url }) subtitles += sub },
                    { link -> if (links.none { it.url == link.url }) links += link },
                )
            }
        } catch (t: Throwable) {
            Logger.error("loadLinks ${api.name}", t)
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    fun play(link: ExtractorLink) {
        val title = listOfNotNull(target.title, target.label).joinToString(" · ")
        try {
            ExternalPlayer.play(link, title, subtitles.toList())
            Store.addHistory(response.toSavedItem(), target.label, target.data)
            nav.toast("Lecture lancée : ${link.name}")
            onDismiss()
        } catch (t: Throwable) {
            nav.toast(t.message ?: "Impossible de lancer le lecteur")
        }
    }

    val sorted = links.sortedWith(compareByDescending<ExtractorLink> { it.quality }.thenBy { it.name })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Choisir une source", fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(target.title, target.label).joinToString(" · "),
                    fontSize = 13.sp, color = CsColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.width(560.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                if (subtitles.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(AppIcons.Subtitles, null, tint = CsColors.TextMuted, modifier = Modifier.size(18.dp))
                        Text(
                            "  ${subtitles.size} sous-titre(s) : " + subtitles.joinToString(", ") { it.lang },
                            fontSize = 12.sp, color = CsColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when {
                    sorted.isEmpty() && loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Recherche des sources…", color = CsColors.TextMuted)
                    }
                    sorted.isEmpty() -> Text(
                        error?.let { "Erreur : $it" } ?: "Aucune source trouvée. Le site a peut-être changé ou la vidéo a été retirée.",
                        color = CsColors.TextMuted,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sorted, key = { it.url }) { link -> LinkRow(link, onPlay = { play(link) }) }
                    }
                }
            }
        },
        confirmButton = {
            if (sorted.isNotEmpty()) {
                TextButton(onClick = { play(sorted.first { ExternalPlayer.isPlayable(it) }) }, enabled = sorted.any { ExternalPlayer.isPlayable(it) }) {
                    Text("Lire la meilleure")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun LinkRow(link: ExtractorLink, onPlay: () -> Unit) {
    val playable = ExternalPlayer.isPlayable(link)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CsColors.SurfaceHigh)
            .clickable(enabled = playable, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(link.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val quality = Qualities.getStringByInt(link.quality).ifBlank { "Qualité inconnue" }
            Text(
                listOf(quality, link.type.name, hostOf(link.url)).distinct().joinToString(" · ") +
                    if (playable) "" else " · non pris en charge",
                fontSize = 12.sp, color = CsColors.TextMuted,
            )
        }
        IconButton(onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link.url), null)
        }) { Icon(AppIcons.ContentCopy, "Copier le lien", tint = CsColors.TextMuted) }
        if (playable) Icon(Icons.Default.PlayArrow, "Lire", tint = CsColors.Primary)
    }
}

private fun hostOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: ""
