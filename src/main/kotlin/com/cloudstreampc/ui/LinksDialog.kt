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
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.player.LinkResolver
import com.cloudstreampc.player.PlaybackManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Fetches the sources of a movie/episode and lets the user pick one, like the app's source picker. */
@Composable
fun LinksDialog(request: PlayRequest, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val target = request.playable
    val links = remember(request) { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember(request) { mutableStateListOf<SubtitleFile>() }
    var loading by remember(request) { mutableStateOf(true) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var started by remember(request) { mutableStateOf(false) }
    val autoPlay = !request.chooseManually && (request.autoPlay || Store.settings.autoPlayBestSource)
    val resumeAt = remember(request) { PlaybackManager.resumePosition(request) }

    fun play(link: ExtractorLink) {
        if (started) return
        try {
            started = true
            PlaybackManager.play(request, link, subtitles.toList())
            nav.toast("Lecture lancée : ${link.name}")
        } catch (t: Throwable) {
            started = false
            nav.toast(t.message ?: "Impossible de lancer le lecteur")
        }
    }

    LaunchedEffect(request) {
        try {
            LinkResolver.load(
                request,
                { sub -> if (subtitles.none { it.url == sub.url }) subtitles += sub },
                { link -> if (links.none { it.url == link.url }) links += link },
            )
        } catch (t: Throwable) {
            Logger.error("loadLinks ${request.api.name}", t)
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    // Automatic mode: play the best source once every extractor answered, or after 10 s if some already did.
    LaunchedEffect(request, autoPlay) {
        if (!autoPlay) return@LaunchedEffect
        var waited = 0
        while (loading && (waited < 10_000 || links.isEmpty())) {
            delay(250)
            waited += 250
        }
        // Subtitles often arrive with the last links: give them a moment.
        if (!loading) delay(300)
        PlaybackManager.bestLink(links, request.failedSources, PlaybackManager.rememberedSource(request))?.let { play(it) }
    }

    val best = PlaybackManager.bestLink(links, request.failedSources, PlaybackManager.rememberedSource(request))
    val sorted = links.sortedWith(compareByDescending<ExtractorLink> { it == best }.thenByDescending { it.quality }.thenBy { it.name })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(if (autoPlay && !started) "Lecture automatique…" else "Choisir une source", fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(target.title, target.label).joinToString(" · "),
                    fontSize = 13.sp, color = CsColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (resumeAt != null) {
                    Text("Reprise à ${formatTime(resumeAt)}", fontSize = 13.sp, color = CsColors.Primary)
                }
                request.failedSources.lastOrNull()?.let { failed ->
                    Text(
                        "« $failed » n'a pas pu être lue. Choisissez une autre source.",
                        fontSize = 13.sp, color = CsColors.Error, modifier = Modifier.padding(top = 4.dp),
                    )
                }
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
                    sorted.isEmpty() -> Column {
                        Text(
                            error?.let { "Erreur : $it" } ?: "Aucune source trouvée. Le site a peut-être changé ou la vidéo a été retirée.",
                            color = CsColors.TextMuted,
                        )
                        // Like Stremio: look for the same title in the other providers.
                        TextButton(onClick = {
                            SearchState.query = request.response.name
                            SearchState.pendingSearch = true
                            onDismiss()
                            nav.select(Tab.Search)
                        }) { Text("Chercher « ${request.response.name} » dans les autres fournisseurs") }
                    }
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sorted, key = { it.url }) { link ->
                            LinkRow(link, recommended = link == best, failed = link.name in request.failedSources, onPlay = { play(link) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (best != null) TextButton(onClick = { play(best) }) { Text("Lire la meilleure") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun LinkRow(link: ExtractorLink, recommended: Boolean, failed: Boolean, onPlay: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(link.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (recommended) {
                    Text(
                        "Recommandée", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp))
                            .background(CsColors.Primary).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            val quality = Qualities.getStringByInt(link.quality).ifBlank { "Qualité inconnue" }
            Text(
                listOf(quality, link.type.name, hostOf(link.url)).distinct().joinToString(" · ") +
                    when {
                        !playable -> " · non pris en charge"
                        failed -> " · échec de lecture"
                        else -> ""
                    },
                fontSize = 12.sp, color = if (failed) CsColors.Error else CsColors.TextMuted,
            )
        }
        IconButton(onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link.url), null)
        }) { Icon(AppIcons.ContentCopy, "Copier le lien", tint = CsColors.TextMuted) }
        if (playable) Icon(Icons.Default.PlayArrow, "Lire", tint = CsColors.Primary)
    }
}

private fun hostOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: ""
