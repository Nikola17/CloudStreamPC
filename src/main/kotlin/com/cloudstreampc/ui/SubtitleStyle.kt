package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Settings
import com.cloudstreampc.core.Store

/** Settings line that opens the subtitle look editor. */
@Composable
fun SubtitleStyleItem() {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    SettingsItem("Apparence des sous-titres", "Taille, couleur, contour, fond, position et décalage, avec un aperçu") { open = true }
    if (open) SubtitleStyleDialog { open = false }
}

private val textColors = listOf(
    0xFFFFFFFF to "Blanc", 0xFFFFEB3B to "Jaune", 0xFF00E5FF to "Cyan", 0xFF76FF03 to "Vert", 0xFFFF80AB to "Rose",
)
private val outlineColors = listOf(0xFF000000 to "Noir", 0xFF424242 to "Gris", 0xFF0D47A1 to "Bleu")

/** Subtitle look, applied to the next videos; the preview mimics mpv's rendering on a 720p picture. */
@Composable
fun SubtitleStyleDialog(onDismiss: () -> Unit) {
    val s = Store.settings
    fun update(change: (Settings) -> Settings) = Store.updateSettings(change)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apparence des sous-titres") },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubtitlePreview(s)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Taille")
                    listOf(42 to "Petite", 55 to "Normale", 68 to "Grande", 82 to "Très grande").forEach { (v, label) ->
                        FilterChip(s.subtitleSize == v, onClick = { update { it.copy(subtitleSize = v) } }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Couleur")
                    textColors.forEach { (v, label) -> Swatch(v, label, s.subtitleColor == v) { update { it.copy(subtitleColor = v) } } }
                    Box(Modifier.width(16.dp))
                    Text("Gras", fontSize = 14.sp)
                    Switch(s.subtitleBold, onCheckedChange = { v -> update { it.copy(subtitleBold = v) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Contour")
                    listOf(0.0 to "Aucun", 1.5 to "Fin", 3.0 to "Normal", 5.0 to "Épais").forEach { (v, label) ->
                        FilterChip(s.subtitleOutlineSize == v, onClick = { update { it.copy(subtitleOutlineSize = v) } }, label = { Text(label) })
                    }
                    outlineColors.forEach { (v, label) -> Swatch(v, label, s.subtitleOutlineColor == v) { update { it.copy(subtitleOutlineColor = v) } } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Fond")
                    listOf(0 to "Aucun", 40 to "Léger", 75 to "Sombre", 100 to "Opaque").forEach { (v, label) ->
                        FilterChip(s.subtitleBackground == v, onClick = { update { it.copy(subtitleBackground = v) } }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Position")
                    listOf(100 to "En bas", 94 to "Un peu plus haut", 86 to "Plus haut").forEach { (v, label) ->
                        FilterChip(s.subtitlePosition == v, onClick = { update { it.copy(subtitlePosition = v) } }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Décalage")
                    OutlinedButton(onClick = { update { it.copy(subtitleDelay = Math.round((it.subtitleDelay - 0.1) * 10) / 10.0) } }) { Text("−0,1 s") }
                    Text(
                        "%+.1f s".format(s.subtitleDelay).replace('.', ','),
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = { update { it.copy(subtitleDelay = Math.round((it.subtitleDelay + 0.1) * 10) / 10.0) } }) { Text("+0,1 s") }
                    if (s.subtitleDelay != 0.0) TextButton(onClick = { update { it.copy(subtitleDelay = 0.0) } }) { Text("Remettre à 0") }
                }
                Text(
                    "Positif : les sous-titres apparaissent plus tard. Pendant la lecture, les touches Z (−0,1 s) et X (+0,1 s) font de même.",
                    color = CsColors.TextMuted, fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } },
        dismissButton = {
            TextButton(onClick = {
                val d = Settings()
                update {
                    it.copy(
                        subtitleSize = d.subtitleSize, subtitleColor = d.subtitleColor, subtitleOutlineColor = d.subtitleOutlineColor,
                        subtitleOutlineSize = d.subtitleOutlineSize, subtitleBackground = d.subtitleBackground,
                        subtitlePosition = d.subtitlePosition, subtitleBold = d.subtitleBold, subtitleDelay = d.subtitleDelay,
                    )
                }
            }) { Text("Réinitialiser") }
        },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, modifier = Modifier.width(80.dp), color = CsColors.TextMuted, fontSize = 14.sp)
}

@Composable
private fun Swatch(color: Long, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) CsColors.Primary else Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable(onClickLabel = I18n.tr(label), onClick = onClick),
    )
}

/** A 16:9 "picture" with the subtitle drawn as mpv would (font size relative to a 720-line picture). */
@Composable
private fun SubtitlePreview(s: Settings) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF5B86A8), Color(0xFFB9A27A), Color(0xFF3B3326)))),
    ) {
        val density = LocalDensity.current
        val scale = with(density) { maxHeight.toPx() } / 720f
        val fontSize = with(density) { (s.subtitleSize * scale).toSp() }
        val outline = (s.subtitleOutlineSize * scale * 2).toFloat()
        // sub-pos: 100 is the bottom margin; each point below moves the line up by 1 % of the height.
        val bottom = maxHeight * (0.035f + (100 - s.subtitlePosition) / 100f)
        val weight = if (s.subtitleBold) FontWeight.Bold else FontWeight.SemiBold
        val line = "Voici à quoi ressembleront les sous-titres."
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 12.dp, end = 12.dp)) {
            val boxColor = Color.Black.copy(alpha = s.subtitleBackground / 100f)
            Box(Modifier.background(if (s.subtitleBackground > 0) boxColor else Color.Transparent).padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (outline > 0f) {
                    androidx.compose.material3.Text(
                        line, fontSize = fontSize, fontWeight = weight, textAlign = TextAlign.Center,
                        style = TextStyle(drawStyle = Stroke(width = outline, join = StrokeJoin.Round)),
                        color = Color(s.subtitleOutlineColor),
                    )
                }
                androidx.compose.material3.Text(line, fontSize = fontSize, fontWeight = weight, textAlign = TextAlign.Center, color = Color(s.subtitleColor))
            }
        }
    }
}
