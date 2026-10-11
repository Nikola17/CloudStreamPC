package com.cloudstreampc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cloudstreampc.ui.SilentVideo
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shows a video with [SilentVideo] in a window, with text over it, for a few seconds.
 * Manual check (needs a display and mpv): -Dtest.trailerVideo=/path/video.mkv
 */
class SilentVideoTest {
    @Test
    fun playsUnderComposeContent() {
        val video = System.getProperty("test.trailerVideo") ?: return
        var frames = 0
        var ended = false
        application(exitProcessOnExit = false) {
            val state = rememberWindowState(width = 1280.dp, height = 640.dp)
            Window(onCloseRequest = ::exitApplication, state = state, title = "SilentVideoTest") {
                var shown by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SilentVideo(video, Modifier.fillMaxSize(), onFirstFrame = { frames++; shown++ }, onEnded = { ended = true })
                    Text("Texte par-dessus la vidéo", color = Color.White, fontSize = 32.sp, modifier = Modifier.align(Alignment.BottomStart).padding(40.dp))
                }
                LaunchedEffect(Unit) {
                    delay(12_000)
                    exitApplication()
                }
            }
        }
        assertTrue(frames > 0, "no frame shown")
    }
}
