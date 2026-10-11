package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.player.NowPlaying
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.player.PlaybackManager
import kotlinx.coroutines.delay

/** Dialogs and banners driven by [PlaybackManager], shown above every screen. */
@Composable
fun PlaybackOverlays(modifier: Modifier = Modifier) {
    PlaybackManager.pending?.let { request ->
        LinksDialog(request, onDismiss = { PlaybackManager.pending = null })
    }
    PlaybackManager.nextUp?.let { next ->
        NextEpisodeDialog(next)
    }
    PlaybackManager.nowPlaying?.let { NowPlayingBar(it, modifier) }
}

@Composable
private fun NextEpisodeDialog(next: PlayRequest) {
    var remaining by remember(next) { mutableIntStateOf(10) }
    LaunchedEffect(next) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        PlaybackManager.request(next)
    }
    AlertDialog(
        onDismissRequest = { PlaybackManager.nextUp = null },
        title = { Text("Épisode suivant", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(next.playable.label ?: next.playable.title, fontWeight = FontWeight.SemiBold)
                Text(next.playable.title, color = CsColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("Lecture dans $remaining s…", color = CsColors.TextMuted)
                LinearProgressIndicator(
                    progress = { 1f - remaining / 10f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { PlaybackManager.request(next) }) {
                Icon(Icons.Default.PlayArrow, null)
                Text(" Lire maintenant")
            }
        },
        dismissButton = { TextButton(onClick = { PlaybackManager.nextUp = null }) { Text("Annuler") } },
    )
}

@Composable
private fun NowPlayingBar(playing: NowPlaying, modifier: Modifier) {
    val playable = playing.request.playable
    Row(
        modifier
            .padding(16.dp)
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CsColors.SurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = CsColors.Primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(playable.title, playable.label?.substringBefore("  ")).joinToString(" · "),
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val position = playing.position
            val duration = playing.duration
            if (position != null && duration != null && duration > 0) {
                LinearProgressIndicator(
                    progress = { (position / duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp),
                    trackColor = Color.White.copy(alpha = 0.15f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Text("${formatTime(position)} / ${formatTime(duration)} · ${playing.sourceName}", fontSize = 12.sp, color = CsColors.TextMuted)
            } else {
                Text("En lecture dans le lecteur · ${playing.sourceName}", fontSize = 12.sp, color = CsColors.TextMuted)
            }
        }
    }
}
