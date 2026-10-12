package com.cloudstreampc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import com.cloudstreampc.core.Logger
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * `--smoke-test=<folder>` (CI): the packaged app goes through its main screens, saves a capture of
 * each one, plays a test picture in the window when mpv is there, then quits. "smoke-ok.txt" is
 * written only when everything went through, so a crash or a missing part of the runtime fails the build.
 */
@Composable
fun SmokeTest(window: ComposeWindow, nav: Navigator, dir: File, exit: () -> Unit) {
    LaunchedEffect(Unit) {
        dir.mkdirs()
        val steps = mutableListOf<String>()
        val failures = mutableListOf<String>()
        suspend fun shot(name: String, wait: Long = 2_500) {
            delay(wait)
            withContext(Dispatchers.IO) {
                runCatching {
                    val image = Robot().createScreenCapture(window.bounds)
                    ImageIO.write(image, "png", File(dir, "$name.png"))
                }.onFailure { Logger.error("Capture $name", it) }
            }
            steps += name
            Logger.info("Test d'interface : $name")
        }
        shot("1-accueil", 8_000)
        nav.select(Tab.Search); shot("2-recherche")
        nav.select(Tab.Library); shot("3-bibliotheque")
        nav.push(Route.Stats); shot("4-statistiques")
        nav.back()
        nav.select(Tab.Downloads); shot("5-telechargements")
        nav.select(Tab.Settings); shot("6-parametres")
        nav.push(Route.Settings(SettingsPage.Player)); shot("7-lecteur")
        nav.back()
        nav.push(Route.Settings(SettingsPage.Extensions)); shot("8-extensions")
        nav.back()
        nav.select(Tab.Home)
        val mpv = com.cloudstreampc.player.ExternalPlayer.findPlayer(com.cloudstreampc.core.PlayerKind.MPV, null)
        if (mpv != null && com.cloudstreampc.player.PlayerSurface.supported) {
            com.cloudstreampc.core.Store.updateSettings { it.copy(playerInWindow = true, fullscreen = false) }
            com.cloudstreampc.player.ExternalPlayer.playTarget("av://lavfi:testsrc=size=1280x720:rate=25:duration=20", "Test")
            shot("9-video", 8_000)
            com.cloudstreampc.player.PlayerSurface.stop()
            delay(2_000)
            if (com.cloudstreampc.player.PlayerSurface.active) failures += "9-video : la zone vidéo reste affichée après « Retour »"
            if (System.getenv("SMOKE_PLAYBACK") == "1") playback({ n, w -> shot(n, w) }, steps, failures)
        }
        if (failures.isNotEmpty()) Logger.error("Test d'interface : ${failures.joinToString(" ; ")}")
        File(dir, if (failures.isEmpty()) "smoke-ok.txt" else "smoke-failed.txt").writeText((steps + failures).joinToString("\n"))
        exit()
    }
}

/**
 * Real playback, the way it is used (CI with the network, `SMOKE_PLAYBACK=1`): a film over HTTP and a
 * live channel, played several times in a row, first in mpv's own window then in the app's window,
 * each one closed like a user does. The app must survive all of it, the picture must move, and the
 * app must be usable again after "Retour".
 */
private suspend fun playback(shot: suspend (String, Long) -> Unit, steps: MutableList<String>, failures: MutableList<String>) {
    val api = object : com.lagradost.cloudstream3.MainAPI() {
        override var name = "Test CI"
        override var mainUrl = "https://example.org"
        override val supportedTypes = setOf(com.lagradost.cloudstream3.TvType.Movie, com.lagradost.cloudstream3.TvType.Live)
    }
    val film = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    val live = "https://live.france24.com/hls/live/2037179-b/F24_FR_HI_HLS/master_5000.m3u8"
    val manager = com.cloudstreampc.player.PlaybackManager
    for (inWindow in listOf(false, true)) {
        com.cloudstreampc.core.Store.updateSettings { it.copy(playerInWindow = inWindow, fullscreen = false) }
        val mode = if (inWindow) "fenetre-app" else "fenetre-mpv"
        repeat(2) { round ->
            for ((kind, url) in listOf("film" to film, "direct" to live)) {
                val name = "10-$mode-$kind-${round + 1}"
                val response = if (kind == "film") {
                    api.newMovieLoadResponse("Film test", film, com.lagradost.cloudstream3.TvType.Movie, film)
                } else {
                    api.newLiveStreamLoadResponse("Direct test", live, live)
                }
                val queue = com.cloudstreampc.player.PlayQueue.build(response, emptyList())
                val request = com.cloudstreampc.player.PlayRequest(api, response, queue, 0, autoPlay = true)
                                val link = com.lagradost.cloudstream3.utils.newExtractorLink("Test", "Test $kind", url, com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8)
                val before = manager.currentIpc
                withContext(Dispatchers.Main) { manager.play(request, link, emptyList()) }
                // Playing: the position moves on.
                var position = 0.0
                val until = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < until && position < 3.0) {
                    delay(500)
                    val ipc = manager.currentIpc?.takeIf { it != before } ?: continue
                    position = withContext(Dispatchers.IO) { com.cloudstreampc.player.MpvIpc.get(ipc, "time-pos")["time-pos"]?.asDouble() } ?: 0.0
                }
                shot(name, 500)
                if (position < 3.0) failures += "$name : la vidéo ne démarre pas" else steps += "$name : lecture OK (${"%.1f".format(position)} s)"
                // Closed like a user does: "Retour" above the video, or mpv's window closed.
                if (inWindow) {
                    com.cloudstreampc.player.PlayerSurface.stop()
                } else {
                    manager.currentIpc?.let { ipc -> withContext(Dispatchers.IO) { com.cloudstreampc.player.MpvIpc.send(ipc, listOf("quit", 0)) } }
                }
                delay(3_000)
                if (com.cloudstreampc.player.PlayerSurface.active) failures += "$name : écran noir après « Retour »"
                shot("$name-retour", 500)
            }
        }
    }
    com.cloudstreampc.core.Store.updateSettings { it.copy(playerInWindow = false) }
}
