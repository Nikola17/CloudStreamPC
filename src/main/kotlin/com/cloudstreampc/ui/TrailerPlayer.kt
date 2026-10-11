package com.cloudstreampc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.player.ExternalPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import kotlin.math.max

private const val FRAME_W = 960
private const val FRAME_H = 540
private const val FPS = 25

/**
 * Plays a video without sound inside the Compose layout (so text can be drawn over it):
 * mpv decodes it to raw frames on its standard output and we paint them.
 */
@Composable
fun SilentVideo(url: String, modifier: Modifier = Modifier, onFirstFrame: () -> Unit = {}, onEnded: () -> Unit = {}) {
    // Three buffers: one shown, one being filled, one spare while the UI may still draw the previous frame.
    val info = remember { ImageInfo(FRAME_W, FRAME_H, ColorType.BGRA_8888, ColorAlphaType.OPAQUE) }
    val bitmaps = remember { List(3) { Bitmap().apply { allocPixels(info) } } }
    val images: List<ImageBitmap> = remember { bitmaps.map { it.asComposeImageBitmap() } }
    var shown by remember(url) { mutableIntStateOf(-1) }
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val ended by rememberUpdatedState(onEnded)

    // Reading a frame blocks: stopping mpv is what ends it when the video leaves the screen.
    val process = remember(url) { arrayOfNulls<Process>(1) }
    DisposableEffect(url) { onDispose { process[0]?.destroy() } }
    DisposableEffect(Unit) { onDispose { bitmaps.forEach { it.close() } } }

    LaunchedEffect(url) {
        val mpv = ExternalPlayer.findPlayer(PlayerKind.MPV, null) ?: return@LaunchedEffect ended()
        val mpvProcess = withContext(Dispatchers.IO) {
            ProcessBuilder(
                mpv.path, "--no-config", "--terminal=no", "--no-audio", "--o=-", "--of=rawvideo", "--ovc=rawvideo",
                "--vf=scale=w=$FRAME_W:h=$FRAME_H:force_original_aspect_ratio=increase,crop=$FRAME_W:$FRAME_H,fps=$FPS,format=bgra",
                "--", url,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
        process[0] = mpvProcess
        try {
            withContext(Dispatchers.IO) {
                val input = DataInputStream(mpvProcess.inputStream.buffered(FRAME_W * FRAME_H * 4))
                val frame = ByteArray(FRAME_W * FRAME_H * 4)
                var next = System.nanoTime()
                var index = 0
                while (isActive) {
                    input.readFully(frame)
                    val target = (index + 1) % bitmaps.size
                    bitmaps[target].installPixels(info, frame, FRAME_W * 4)
                    bitmaps[target].notifyPixelsChanged()
                    index = target
                    withContext(Dispatchers.Main) {
                        if (shown < 0) firstFrame()
                        shown = target
                    }
                    next += 1_000_000_000L / FPS
                    val wait = next - System.nanoTime()
                    if (wait > 0) delay(wait / 1_000_000) else next = System.nanoTime()
                }
            }
        } catch (_: java.io.IOException) {
            // End of the video, or mpv stopped.
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.error("Bande-annonce", t)
        } finally {
            mpvProcess.destroy()
        }
        ended()
    }

    Canvas(modifier) {
        val current = shown
        if (current < 0) return@Canvas
        // Fill the area like ContentScale.Crop, centered.
        val scale = max(size.width / FRAME_W, size.height / FRAME_H)
        val srcW = (size.width / scale).toInt().coerceAtMost(FRAME_W)
        val srcH = (size.height / scale).toInt().coerceAtMost(FRAME_H)
        drawImage(
            images[current],
            srcOffset = IntOffset((FRAME_W - srcW) / 2, (FRAME_H - srcH) / 2),
            srcSize = IntSize(srcW, srcH),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
