package com.cloudstreampc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import com.cloudstreampc.core.Logger
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
            com.cloudstreampc.core.Store.updateSettings { it.copy(embeddedPlayer = true, fullscreen = false) }
            com.cloudstreampc.player.ExternalPlayer.playTarget("av://lavfi:testsrc=size=1280x720:rate=25:duration=20", "Test")
            shot("9-video", 8_000)
            com.cloudstreampc.player.PlayerSurface.stop()
            delay(2_000)
        }
        File(dir, "smoke-ok.txt").writeText(steps.joinToString("\n"))
        exit()
    }
}
