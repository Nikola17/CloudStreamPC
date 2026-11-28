package com.moncloudi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moncloudi.core.Store
import com.moncloudi.player.OnlineSubtitles
import com.moncloudi.player.PlaybackManager
import com.moncloudi.player.PlayerController
import com.moncloudi.player.PlayerSurface
import com.moncloudi.player.VideoAspectMode
import com.lagradost.cloudstream3.utils.Qualities
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

private enum class PlayerPanel(val title: String) {
    SUBTITLES("Sous-titres"),
    AUDIO("Pistes audio"),
    SOURCES("Sources (VF / VOSTFR)"),
    EPISODES("Épisodes"),
    SETTINGS("Lecture & Image"),
}

/**
 * The integrated video player inside MonCloudi's window: a sleek dark top bar with direct access
 * to Audio, Subtitles (+ delay/size/file/OpenSubtitles), Sources (VF/VOSTFR), Episodes, Speed/Zoom,
 * PiP and Fullscreen, paired with a non-overlapping side panel beside the native mpv SwingPanel so
 * Windows heavyweight rendering never clips the controls.
 */
@Composable
fun EmbeddedPlayer(modifier: Modifier = Modifier) {
    var activePanel by remember { mutableStateOf<PlayerPanel?>(null) }

    // Sync Escape handling with Main.kt (Escape closes the side panel first before leaving the player).
    LaunchedEffect(activePanel) {
        PlayerSurface.panelOpen = activePanel != null
        if (activePanel == null) PlayerSurface.onFocusVideo?.invoke()
    }
    LaunchedEffect(PlayerSurface.panelOpen) {
        if (!PlayerSurface.panelOpen && activePanel != null) activePanel = null
    }
    LaunchedEffect(PlayerSurface.fullscreen, PlayerSurface.pip) {
        if (PlayerSurface.fullscreen || PlayerSurface.pip) activePanel = null
    }

    fun togglePanel(panel: PlayerPanel) {
        activePanel = if (activePanel == panel) null else panel
    }

    val nowPlaying = PlaybackManager.nowPlaying
    val sources = PlaybackManager.availableSources
    val queue = nowPlaying?.request?.queue.orEmpty()
    val isLive = nowPlaying?.request?.isLive == true
    val audioTracks = PlayerController.audioTracks
    val subTracks = PlayerController.subtitleTracks
    val activeSub = subTracks.firstOrNull { it.id == PlayerController.activeSubId && PlayerController.subVisible }
    val activeAudio = audioTracks.firstOrNull { it.id == PlayerController.activeAudioId || it.selected }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        if (!PlayerSurface.fullscreen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(if (PlayerSurface.pip) 36.dp else 52.dp)
                    .background(Color(0xFF0E0E12))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = PlayerSurface::stop) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, I18n.tr("Fermer la vidéo"), tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = PlayerSurface.title,
                        color = Color.White,
                        style = if (PlayerSurface.pip) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!PlayerSurface.pip && nowPlaying != null) {
                        Text(
                            text = nowPlaying.sourceName,
                            color = CsColors.TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!PlayerSurface.pip) {
                    if (sources.size > 1) {
                        PlayerBarPill(
                            icon = AppIcons.Tune,
                            label = "Sources (${sources.size})",
                            active = activePanel == PlayerPanel.SOURCES,
                            onClick = { togglePanel(PlayerPanel.SOURCES) },
                        )
                    }
                    PlayerBarPill(
                        icon = AppIcons.GraphicEq,
                        label = activeAudio?.lang?.takeIf { it.isNotBlank() && it != "und" }
                            ?.let { "Audio · ${it.uppercase().take(3)}" } ?: "Audio",
                        active = activePanel == PlayerPanel.AUDIO,
                        onClick = { togglePanel(PlayerPanel.AUDIO) },
                    )
                    if (!isLive) {
                        PlayerBarPill(
                            icon = AppIcons.Subtitles,
                            label = if (activeSub != null) {
                                "ST · ${(activeSub.lang ?: "ON").uppercase().take(3)}"
                            } else if (subTracks.isNotEmpty()) {
                                "ST (${subTracks.size})"
                            } else {
                                "Sous-titres"
                            },
                            active = activePanel == PlayerPanel.SUBTITLES,
                            highlight = activeSub != null,
                            onClick = { togglePanel(PlayerPanel.SUBTITLES) },
                        )
                    }
                    if (!isLive && queue.size > 1) {
                        PlayerBarPill(
                            icon = AppIcons.PlaylistPlay,
                            label = "Épisodes",
                            active = activePanel == PlayerPanel.EPISODES,
                            onClick = { togglePanel(PlayerPanel.EPISODES) },
                        )
                    }
                    IconButton(onClick = { togglePanel(PlayerPanel.SETTINGS) }) {
                        Icon(
                            AppIcons.AspectRatio,
                            I18n.tr("Lecture & Image"),
                            tint = if (activePanel == PlayerPanel.SETTINGS) CsColors.Primary else Color.White,
                        )
                    }
                }
                IconButton(onClick = { PlayerSurface.switchPip(!PlayerSurface.pip) }) {
                    Icon(
                        if (PlayerSurface.pip) AppIcons.OpenInFull else AppIcons.PictureInPicture,
                        I18n.tr(if (PlayerSurface.pip) "Agrandir" else "Image dans l'image"),
                        tint = Color.White,
                    )
                }
                if (!PlayerSurface.pip) {
                    IconButton(onClick = { PlayerSurface.switchFullscreen(true) }) {
                        Icon(AppIcons.Fullscreen, I18n.tr("Plein écran"), tint = Color.White)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    factory = { VideoCanvas() },
                )
            }
            val panel = activePanel
            if (panel != null && !PlayerSurface.fullscreen && !PlayerSurface.pip) {
                PlayerSidePanel(
                    panel = panel,
                    onClose = { activePanel = null },
                )
            }
        }
    }
}

@Composable
private fun PlayerBarPill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        active -> CsColors.Primary.copy(alpha = 0.25f)
        highlight -> Color.White.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.07f)
    }
    val border = if (active) CsColors.Primary else Color.Transparent
    val tint = if (active || highlight) Color.White else Color(0xFFD8D8E0)
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (active) CsColors.Primary else tint, modifier = Modifier.size(17.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerSidePanel(panel: PlayerPanel, onClose: () -> Unit) {
    Column(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(Color(0xFF13131A))
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(panel.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, I18n.tr("Fermer"), tint = CsColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.08f))

        when (panel) {
            PlayerPanel.SUBTITLES -> SubtitlesPanelContent()
            PlayerPanel.AUDIO -> AudioPanelContent()
            PlayerPanel.SOURCES -> SourcesPanelContent(onPicked = onClose)
            PlayerPanel.EPISODES -> EpisodesPanelContent(onPicked = onClose)
            PlayerPanel.SETTINGS -> QuickSettingsPanelContent()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitlesPanelContent() {
    val tracks = PlayerController.subtitleTracks
    val activeId = PlayerController.activeSubId.takeIf { PlayerController.subVisible }
    val delaySec = PlayerController.subDelay
    val scale = PlayerController.subScale

    Column(Modifier.fillMaxSize()) {
        // Quick subtitle delay & scale controls
        Text("Synchronisation", color = CsColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallActionChip("-0.25 s") { PlayerController.adjustSubDelay(-0.25) }
            SmallActionChip(
                "%+.2f s".format(delaySec),
                highlighted = delaySec != 0.0,
            ) { PlayerController.changeSubDelay(0.0) }
            SmallActionChip("+0.25 s") { PlayerController.adjustSubDelay(0.25) }
        }
        Spacer(Modifier.height(10.dp))
        Text("Taille", color = CsColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallActionChip("A-") { PlayerController.adjustSubScale(-0.1) }
            SmallActionChip("${(scale * 100).roundToInt()} %", highlighted = scale != 1.0) { PlayerController.changeSubScale(1.0) }
            SmallActionChip("A+") { PlayerController.adjustSubScale(0.1) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { PlaybackManager.reloadOnlineSubtitles() },
                enabled = !PlayerController.loadingOnlineSubtitles,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (PlayerController.loadingOnlineSubtitles) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("OpenSubtitles", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Charger un fichier de sous-titres"
                        fileFilter = FileNameExtensionFilter("Sous-titres (.srt, .vtt, .ass, .ssa)", "srt", "vtt", "ass", "ssa", "sub")
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        PlayerController.addLocalSubtitle(chooser.selectedFile)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Fichier .srt/.vtt", fontSize = 12.sp)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.08f))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                PanelChoiceRow(
                    title = "Désactivés",
                    subtitle = "Masquer les sous-titres",
                    selected = activeId == null,
                    onClick = { PlayerController.selectSubtitle(null) },
                )
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        if (PlayerController.loadingOnlineSubtitles) "Recherche de sous-titres en cours…"
                        else "Aucune piste de sous-titres détectée pour le moment.",
                        color = CsColors.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
            } else {
                items(tracks, key = { it.id }) { track ->
                    PanelChoiceRow(
                        title = track.label,
                        subtitle = listOfNotNull(track.codec?.uppercase(), track.lang?.let(OnlineSubtitles::displayLanguage)).distinct().joinToString(" · "),
                        selected = activeId == track.id,
                        onClick = { PlayerController.selectSubtitle(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPanelContent() {
    val tracks = PlayerController.audioTracks
    val activeId = PlayerController.activeAudioId
    val sources = PlaybackManager.availableSources

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tracks.isEmpty()) {
            item {
                Text("Chargement des pistes audio…", color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        } else {
            items(tracks, key = { it.id }) { track ->
                val isSelected = if (activeId != null) track.id == activeId else track.selected
                PanelChoiceRow(
                    title = track.label,
                    subtitle = listOfNotNull(track.codec?.uppercase(), track.channels?.let { "${it}ch" }).joinToString(" · "),
                    selected = isSelected,
                    onClick = { PlayerController.selectAudio(track.id) },
                )
            }
        }
        if (tracks.size <= 1 && sources.size > 1) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ce flux ne contient qu'une piste audio. Utilisez l'onglet « Sources » en haut pour basculer entre VF et VOSTFR.",
                    color = CsColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun SourcesPanelContent(onPicked: () -> Unit) {
    val sources = PlaybackManager.availableSources
    val currentUrl = PlaybackManager.currentSourceUrl
    val currentName = PlaybackManager.nowPlaying?.sourceName

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sources.isEmpty()) {
            item {
                Text("Aucune autre source disponible.", color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        } else {
            items(sources, key = { it.url }) { link ->
                val selected = link.url == currentUrl || (currentUrl == null && link.name == currentName)
                val quality = Qualities.getStringByInt(link.quality).ifBlank { link.type.name }
                val host = runCatching { java.net.URI(link.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
                PanelChoiceRow(
                    title = link.name,
                    subtitle = listOf(quality, host).filter { it.isNotBlank() }.joinToString(" · "),
                    selected = selected,
                    onClick = {
                        if (!selected) PlaybackManager.switchSource(link)
                        onPicked()
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodesPanelContent(onPicked: () -> Unit) {
    val request = PlaybackManager.nowPlaying?.request ?: return
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(request.queue, key = { idx, ep -> "$idx-${ep.data}" }) { index, ep ->
            val current = index == request.index
            val watched = Store.isWatched(request.api.name, ep.data)
            PanelChoiceRow(
                title = ep.label ?: ep.title,
                subtitle = when {
                    current -> "En cours de lecture"
                    watched -> "Déjà vu"
                    else -> ""
                },
                selected = current,
                onClick = {
                    if (!current) PlaybackManager.switchEpisode(index)
                    onPicked()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingsPanelContent() {
    val speed = PlayerController.speed
    val volume = PlayerController.volume
    val muted = PlayerController.muted
    val aspect = PlayerController.aspectMode
    val isLive = PlaybackManager.nowPlaying?.request?.isLive == true

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Volume
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { PlayerController.toggleMute() }, modifier = Modifier.size(32.dp)) {
                Icon(if (muted || volume == 0) AppIcons.VolumeOff else AppIcons.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Slider(
                value = volume.toFloat(),
                onValueChange = { PlayerController.changeVolume(it.roundToInt()) },
                valueRange = 0f..150f,
                modifier = Modifier.weight(1f),
            )
            Text(if (muted) "Muet" else "$volume %", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(44.dp))
        }

        if (!isLive) {
            Text("Vitesse de lecture", color = CsColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).forEach { s ->
                    SmallActionChip(
                        label = "${s}x".replace(".0x", "x"),
                        highlighted = kotlin.math.abs(speed - s) < 0.02,
                    ) { PlayerController.changeSpeed(s) }
                }
            }
        }

        Text("Format d'image & Zoom", color = CsColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VideoAspectMode.entries.forEach { mode ->
                PanelChoiceRow(
                    title = mode.label,
                    subtitle = "",
                    selected = aspect == mode,
                    onClick = { PlayerController.setAspect(mode) },
                )
            }
        }
    }
}

@Composable
private fun SmallActionChip(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) CsColors.Primary else Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PanelChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CsColors.Primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) Color.White else Color(0xFFE5E5EC),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = CsColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, null, tint = CsColors.Primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Native panel handed to mpv; keys typed while it has the focus go to mpv. */
private class VideoCanvas : NativeCanvas() {
    init {
        background = java.awt.Color.BLACK
        isFocusable = true
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val special = e.keyCode in specialKeys || e.isControlDown || e.isAltDown
                if (!special) return
                PlayerSurface.mpvKey(e.keyCode, e.keyChar, e.isControlDown, e.isAltDown, e.isShiftDown)?.let(PlayerSurface::key)
                e.consume()
            }

            override fun keyTyped(e: KeyEvent) {
                if (e.isControlDown || e.isAltDown || e.keyChar == ' ' || e.keyChar.isISOControl()) return
                PlayerSurface.key(e.keyChar.toString())
                e.consume()
            }
        })
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                requestFocusInWindow()
            }
        })
    }

    override fun addNotify() {
        super.addNotify()
        PlayerSurface.onFocusVideo = { SwingUtilities.invokeLater { requestFocusInWindow() } }
        // The native window exists once added; its id can be read a moment later.
        SwingUtilities.invokeLater(::publish)
    }

    private fun publish(attempt: Int = 0) {
        val id = nativeId().takeIf { it != 0L }
        if (id != null) {
            PlayerSurface.attached(id)
            requestFocusInWindow()
        } else if (attempt < 20) {
            javax.swing.Timer(100) { publish(attempt + 1) }.apply { isRepeats = false; start() }
        }
    }

    override fun removeNotify() {
        PlayerSurface.onFocusVideo = null
        PlayerSurface.detached()
        super.removeNotify()
    }

    companion object {
        private val specialKeys = setOf(
            KeyEvent.VK_SPACE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER,
            KeyEvent.VK_ESCAPE, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_TAB,
            KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE,
        ) + (KeyEvent.VK_F1..KeyEvent.VK_F12)
    }
}
