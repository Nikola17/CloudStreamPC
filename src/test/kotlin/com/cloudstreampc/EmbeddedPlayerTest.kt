package com.cloudstreampc

import androidx.compose.ui.awt.ComposeWindow
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.ExternalPlayer
import com.cloudstreampc.player.PlayerSurface
import com.cloudstreampc.ui.EmbeddedPlayer
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The video drawn inside the app's window: mpv paints its picture in our panel, keys typed there
 * reach it, and closing the video frees the panel. Needs a display, mpv and ffmpeg; skipped otherwise.
 */
class EmbeddedPlayerTest {
    @Test
    fun mpvDrawsInsideTheWindow() {
        val linux = "linux" in System.getProperty("os.name").lowercase()
        if (GraphicsEnvironment.isHeadless() || (linux && System.getenv("DISPLAY").isNullOrBlank()) || !PlayerSurface.supported) return
        val mpv = (listOfNotNull(System.getProperty("test.mpv")) + listOf("/usr/bin/mpv", "/usr/local/bin/mpv"))
            .map(::File).firstOrNull { it.isFile } ?: return
        Store.updateSettings { it.copy(player = PlayerKind.MPV, playerPath = mpv.path, embeddedPlayer = true, playerInterface = false, subtitleBackground = 60) }

        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.setBounds(40, 40, 640, 460)
            window.setContent { EmbeddedPlayer() }
            window.isVisible = true
        }
        try {
            // A test picture made by mpv itself: colored bars for 30 s.
            ExternalPlayer.playTarget("av://lavfi:testsrc=size=640x360:rate=25:duration=30", "Essai")
            val deadline = System.currentTimeMillis() + 15_000
            while (PlayerSurface.ipcPath == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val ipc = PlayerSurface.ipcPath
            assertTrue(ipc != null, "mpv n'a pas été lancé dans la zone vidéo")
            Thread.sleep(4_000)

            // testsrc: colored bars. Count the colorful pixels in the video area of our window.
            val area = window.contentPane.locationOnScreen.let { java.awt.Rectangle(it.x + 20, it.y + 80, 600, 300) }
            val shot = Robot().createScreenCapture(area)
            javax.imageio.ImageIO.write(shot, "png", File(System.getProperty("java.io.tmpdir"), "cspc-embed-test.png"))
            var colorful = 0
            for (x in 0 until shot.width step 10) for (y in 0 until shot.height step 10) {
                val rgb = shot.getRGB(x, y)
                val r = rgb shr 16 and 0xFF
                val g = rgb shr 8 and 0xFF
                val b = rgb and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 80) colorful++
            }
            println("Pixels colorés dans la fenêtre : $colorful")
            assertTrue(colorful > 300, "L'image de mpv n'apparaît pas dans la fenêtre")

            // Keys typed in the panel reach mpv: "space" pauses.
            PlayerSurface.key("SPACE")
            Thread.sleep(800)
            val paused = com.cloudstreampc.player.MpvIpc.get(ipc!!, "pause")["pause"]?.asText()
            println("Pause après ESPACE : $paused")
            assertTrue(paused == "true", "La touche n'est pas arrivée à mpv")

            PlayerSurface.stop()
            val end = System.currentTimeMillis() + 5_000
            while (PlayerSurface.ipcPath != null && System.currentTimeMillis() < end) Thread.sleep(100)
            assertTrue(PlayerSurface.ipcPath == null, "La vidéo ne s'est pas fermée")
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
