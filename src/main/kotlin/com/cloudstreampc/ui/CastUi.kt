package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.cast.CastManager
import com.cloudstreampc.player.PlayRequest
import kotlinx.coroutines.launch

/** Lists the TVs of the network and sends them [request]. */
@Composable
fun CastDialog(request: PlayRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { CastManager.discover() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Caster sur un appareil", fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(request.playable.title, request.playable.label?.substringBefore("  ")).joinToString(" · "),
                    fontSize = 13.sp, color = CsColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (CastManager.searching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Recherche des Chromecast et TV DLNA du réseau…", color = CsColors.TextMuted, fontSize = 13.sp)
                    }
                } else if (CastManager.devices.isEmpty()) {
                    Text(
                        "Aucun appareil trouvé. Vérifiez que la TV est allumée et sur le même réseau que ce PC, et que le " +
                            "pare-feu Windows autorise CloudStreamPC (réseau privé).",
                        color = CsColors.TextMuted, fontSize = 13.sp,
                    )
                }
                CastManager.devices.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CsColors.SurfaceHigh)
                            .clickable(enabled = !busy) {
                                busy = true
                                error = false
                                scope.launch {
                                    runCatching { CastManager.cast(device, request) { status = it } }
                                        .onSuccess { nav.toast("Lecture sur ${device.name}"); onDismiss() }
                                        .onFailure {
                                            status = it.message ?: "Échec de la diffusion"
                                            error = true
                                        }
                                    busy = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Cast, null, tint = CsColors.Primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text("${device.kind} · ${device.host}", fontSize = 12.sp, color = CsColors.TextMuted)
                        }
                    }
                }
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(it, color = if (error) CsColors.Error else CsColors.TextMuted, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !CastManager.searching, onClick = { scope.launch { CastManager.discover() } }) { Text("Rechercher à nouveau") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** "Sur la TV du salon · MobLand" with pause and stop, while something is cast. */
@Composable
fun CastBar(modifier: Modifier = Modifier) {
    val now = CastManager.current ?: return
    val scope = rememberCoroutineScope()
    Row(
        modifier.padding(16.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(12.dp)).background(CsColors.SurfaceHigh)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Cast, null, tint = CsColors.Primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(now.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Sur ${now.target.name}", fontSize = 12.sp, color = CsColors.TextMuted)
        }
        IconButton(onClick = { scope.launch { CastManager.togglePause() } }) {
            Icon(if (now.paused) Icons.Default.PlayArrow else AppIcons.Pause, if (now.paused) "Lecture" else "Pause")
        }
        IconButton(onClick = { scope.launch { CastManager.stop() } }) { Icon(Icons.Default.Close, "Arrêter") }
    }
}
