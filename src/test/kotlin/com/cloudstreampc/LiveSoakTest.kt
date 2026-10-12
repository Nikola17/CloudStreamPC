package com.cloudstreampc

import com.cloudstreampc.core.PlayerKind
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.ExternalPlayer
import com.cloudstreampc.player.LiveRelay
import com.cloudstreampc.player.LiveSource
import com.cloudstreampc.player.MpvIpc
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Live TV endurance test, the way the app plays a channel: the relay in front of a provider, mpv
 * with the live options. Run by hand against a misbehaving provider:
 *   -Dtest.soak=600 -Dtest.soak.sources=http://127.0.0.1:18080/a/1.ts,http://127.0.0.1:18080/b/1.ts
 * Reports the cuts the viewer would see: buffering pauses, frozen picture, end of stream, player closing.
 */
class LiveSoakTest {
    @Test
    fun liveKeepsPlaying() {
        val seconds = System.getProperty("test.soak")?.toIntOrNull() ?: return
        val urls = System.getProperty("test.soak.sources")?.split(',')?.filter { it.isNotBlank() } ?: return
        val mpv = (listOfNotNull(System.getProperty("test.mpv")) + listOf("/usr/bin/mpv", "/usr/local/bin/mpv"))
            .map(::File).firstOrNull { it.isFile } ?: return
        Store.updateSettings { it.copy(player = PlayerKind.MPV, playerPath = mpv.path, playerInterface = false, fullscreen = false, liveBuffer = 8) }

        val relayUrl = LiveRelay.open(urls.mapIndexed { i, u -> LiveSource(u, label = "source ${i + 1}") })
        val link = runBlocking { newExtractorLink("soak", "Direct", relayUrl, ExtractorLinkType.VIDEO) }
        val ipc = MpvIpc.newServerPath()
        val start = System.currentTimeMillis()
        val process = ExternalPlayer.play(link, "Direct", emptyList(), ipcPath = ipc, live = true)

        var firstPlay: Long? = null
        var pauses = 0
        var pausedSince: Long? = null
        var pausedMs = 0L
        var eofs = 0
        var lastPos: Double? = null
        var lastAdvance = System.currentTimeMillis()
        var freezes = 0
        var frozen = false
        var paused = false
        val watcher = Thread {
            MpvIpc.watch(ipc, process, listOf("paused-for-cache", "time-pos", "eof-reached")) { name, value ->
                val now = System.currentTimeMillis()
                when (name) {
                    "paused-for-cache" -> if (value.asBoolean() && firstPlay != null && !paused) {
                        paused = true; pauses++; pausedSince = now
                        println("%6.1fs pause de mise en tampon".format((now - start) / 1000.0))
                    } else if (!value.asBoolean() && paused) {
                        paused = false; pausedMs += now - (pausedSince ?: now)
                        println("%6.1fs reprise après %.1f s".format((now - start) / 1000.0, (now - (pausedSince ?: now)) / 1000.0))
                    }
                    "eof-reached" -> if (value.asBoolean()) { eofs++; println("%6.1fs fin du flux".format((now - start) / 1000.0)) }
                    "time-pos" -> if (value.isNumber) {
                        if (firstPlay == null) { firstPlay = now; println("%6.1fs lecture démarrée".format((now - start) / 1000.0)) }
                        if (lastPos == null || value.asDouble() > lastPos!!) { lastPos = value.asDouble(); lastAdvance = now; frozen = false }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val end = start + seconds * 1000L
        while (System.currentTimeMillis() < end && process.isAlive) {
            Thread.sleep(250)
            val now = System.currentTimeMillis()
            // Picture stopped without a buffering pause: also a cut for the viewer.
            if (firstPlay != null && !paused && !frozen && now - lastAdvance > 1_500) {
                frozen = true; freezes++
                println("%6.1fs image figée".format((now - start) / 1000.0))
            }
        }
        val closed = !process.isAlive
        val reconnects = LiveRelay.reconnects
        val source = LiveRelay.currentSource?.label
        process.destroy()
        LiveRelay.close()
        watcher.join(2_000)
        println(
            "RÉSULTAT durée=${seconds}s démarrage=%.1fs pauses=$pauses temps_en_pause=%.1fs images_figées=$freezes fins=$eofs lecteur_fermé=$closed reconnexions_relais=$reconnects source_finale=$source connexions_max=${LiveRelay.maxOpenConnections.get()} position=%.0fs"
                .format(((firstPlay ?: start) - start) / 1000.0, pausedMs / 1000.0, lastPos ?: 0.0),
        )
    }
}
