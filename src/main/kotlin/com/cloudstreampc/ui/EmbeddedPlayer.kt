package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudstreampc.player.PlayerSurface
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * The video inside the window: a bar (back, picture in picture, full screen) above the native
 * panel mpv draws into. The bar is hidden in full screen; in picture in picture it shrinks to the
 * two buttons that matter there.
 */
@Composable
fun EmbeddedPlayer(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Color.Black)) {
        if (!PlayerSurface.fullscreen) {
            Row(
                Modifier.fillMaxWidth().height(if (PlayerSurface.pip) 34.dp else 48.dp).background(Color(0xFF0B0B0B)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = PlayerSurface::stop) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, I18n.tr("Fermer la vidéo"), tint = Color.White)
                }
                Text(
                    text = PlayerSurface.title,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = if (PlayerSurface.pip) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SwingPanel(
                background = Color.Black,
                modifier = Modifier.fillMaxSize(),
                factory = { VideoCanvas() },
            )
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
