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
 * Live TV endurance test, the way the app plays channels: the relay in front of the provider, mpv
 * with the live options, and zapping in the same mpv window (`loadfile replace`). Run by hand:
 *
 *   one channel with backups:  -Dtest.soak=600 -Dtest.soak.sources=http://…/a/1.ts,http://…/b/1.ts
 *   several channels in a row: -Dtest.soak=300 "-Dtest.soak.channels=France 24=https://…;;Euronews=https://…"
 *   (backups of a channel separated by |, like the app's .ts then .m3u8)
 *
 * Reports what the viewer would see: buffering pauses, frozen picture, end of stream, player
 * closing, and how long each zap took.
 */
class LiveSoakTest {
    private class Watch {
        @Volatile var firstPlay: Long? = null
        @Volatile var pauses = 0
        @Volatile var pausedSince: Long? = null
        @Volatile var pausedMs = 0L
        @Volatile var eofs = 0
        @Volatile var lastPos: Double? = null
        @Volatile var lastAdvance = System.currentTimeMillis()
        @Volatile var freezes = 0
        @Volatile var frozen = false
        @Volatile var paused = false
        @Volatile var since = System.currentTimeMillis()
        @Volatile var channel = ""
        /** After a zap: positions of the old channel until the new one is loaded. */
        @Volatile var awaitingFile = false

        /** A new channel: counters start again, the first picture marks the end of the zap. */
        fun restart() {
            firstPlay = null; pauses = 0; pausedSince = null; pausedMs = 0; eofs = 0; lastPos = null
            freezes = 0; frozen = false; paused = false; since = System.currentTimeMillis(); lastAdvance = since
        }
    }

    @Test
    fun liveKeepsPlaying() {
        val seconds = System.getProperty("test.soak")?.toIntOrNull() ?: return
        fun plain(name: String, urls: List<String>) = name to urls.mapIndexed { i, u -> LiveSource(u, label = "$name ${i + 1}") }
        val channels: List<Pair<String, List<LiveSource>>> = iptvChannels()
            ?: System.getProperty("test.soak.channels")?.split(";;")
                ?.filter { '=' in it }?.map { plain(it.substringBefore('=').trim(), it.substringAfter('=').split('|').map(String::trim).filter(String::isNotEmpty)) }
            ?: System.getProperty("test.soak.sources")?.split(',')?.filter { it.isNotBlank() }?.let { listOf(plain("Direct", it)) }
            ?: return
        val mpv = (listOfNotNull(System.getProperty("test.mpv")) + listOf("/usr/bin/mpv", "/usr/local/bin/mpv"))
            .map(::File).firstOrNull { it.isFile } ?: return
        Store.updateSettings { it.copy(player = PlayerKind.MPV, playerPath = mpv.path, playerInterface = false, fullscreen = false, liveBuffer = System.getProperty("test.soak.buffer")?.toIntOrNull() ?: 8) }

        val ipc = MpvIpc.newServerPath()
        val watch = Watch()
        var process: Process? = null
        val results = mutableListOf<String>()
        try {
            channels.forEachIndexed { index, (name, sources) ->
                val relayUrl = LiveRelay.open(sources)
                watch.restart()
                watch.channel = name
                watch.awaitingFile = index > 0
                if (index == 0) {
                    val link = runBlocking { newExtractorLink("soak", name, relayUrl, ExtractorLinkType.VIDEO) }
                    process = ExternalPlayer.play(link, name, emptyList(), ipcPath = ipc, live = true)
                    val started = process!!
                    Thread {
                        MpvIpc.watch(
                            ipc, started, listOf("paused-for-cache", "time-pos", "eof-reached"),
                            onEvent = { event, _ -> if (event == "file-loaded") watch.awaitingFile = false },
                        ) { property, value ->
                            val now = System.currentTimeMillis()
                            val t = (now - watch.since) / 1000.0
                            when (property) {
                                "paused-for-cache" -> if (value.asBoolean() && watch.firstPlay != null && !watch.paused) {
                                    watch.paused = true; watch.pauses++; watch.pausedSince = now
                                    println("  [${watch.channel}] %6.1fs pause de mise en tampon".format(t))
                                } else if (!value.asBoolean() && watch.paused) {
                                    watch.paused = false; watch.pausedMs += now - (watch.pausedSince ?: now)
                                    println("  [${watch.channel}] %6.1fs reprise après %.1f s".format(t, (now - (watch.pausedSince ?: now)) / 1000.0))
                                }
                                "eof-reached" -> if (value.asBoolean()) { watch.eofs++; println("  [${watch.channel}] %6.1fs fin du flux".format(t)) }
                                "time-pos" -> if (value.isNumber && !watch.awaitingFile) {
                                    if (watch.firstPlay == null) { watch.firstPlay = now; watch.lastPos = null }
                                    val previous = watch.lastPos
                                    // Any change is the picture moving: after a change of source the clock can start lower.
                                    if (previous == null || value.asDouble() != previous) {
                                        if (watch.frozen) println("  [${watch.channel}] %6.1fs image repartie après %.1f s".format(t, (now - watch.lastAdvance) / 1000.0))
                                        watch.lastPos = value.asDouble(); watch.lastAdvance = now; watch.frozen = false
                                    }
                                }
                            }
                        }
                    }.apply { isDaemon = true; start() }
                } else {
                    // Zapping, as the app does: same window, the new channel replaces the old one.
                    MpvIpc.send(ipc, listOf("set_property", "force-media-title", name), listOf("loadfile", relayUrl, "replace"), listOf("set_property", "pause", false))
                }
                val end = watch.since + seconds * 1000L
                while (System.currentTimeMillis() < end && process?.isAlive == true) {
                    Thread.sleep(250)
                    val now = System.currentTimeMillis()
                    // Picture stopped without a buffering pause: also a cut for the viewer.
                    if (watch.firstPlay != null && !watch.paused && !watch.frozen && now - watch.lastAdvance > 1_500) {
                        watch.frozen = true; watch.freezes++
                        println("  [$name] %6.1fs image figée".format((now - watch.since) / 1000.0))
                    }
                }
                val line = "RÉSULTAT [$name] durée=${seconds}s ${if (index == 0) "démarrage" else "zapping"}=%.1fs pauses=${watch.pauses} temps_en_pause=%.1fs images_figées=${watch.freezes} fins=${watch.eofs} lecteur_fermé=${process?.isAlive != true} reconnexions_relais=${LiveRelay.reconnects} connexions_max=${LiveRelay.maxOpenConnections.get()}"
                    .format(((watch.firstPlay ?: watch.since) - watch.since) / 1000.0, watch.pausedMs / 1000.0)
                println(line)
                results += line
            }
        } finally {
            process?.destroy()
            LiveRelay.close()
        }
        println("BILAN\n" + results.joinToString("\n"))
    }

    /**
     * A real Xtream account, the way the app plays it: `-Dtest.soak.iptv=http://host|user|password` and
     * `-Dtest.soak.iptv.ids=275681,275758` (stream ids). Each channel gets the backups the app builds
     * (other format, copies of the channel on the account, other accounts).
     */
    private fun iptvChannels(): List<Pair<String, List<LiveSource>>>? {
        val (url, user, password) = System.getProperty("test.soak.iptv")?.split('|')?.takeIf { it.size == 3 } ?: return null
        val ids = System.getProperty("test.soak.iptv.ids")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: return null
        val provider = com.cloudstreampc.providers.IptvProvider(com.cloudstreampc.core.IptvAccount("soak", "Soak", com.cloudstreampc.core.IptvType.XTREAM, url, user, password))
        return runBlocking {
            ids.mapNotNull { id ->
                val response = provider.load("iptv://soak/live/$id") ?: return@mapNotNull null
                val queue = com.cloudstreampc.player.PlayQueue.build(response, emptyList())
                val request = com.cloudstreampc.player.PlayRequest(provider, response, queue, 0, autoPlay = true)
                val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
                provider.loadLinks(queue.first().data, false, {}) { links += it }
                val chosen = com.cloudstreampc.player.PlaybackManager.bestLink(links) ?: return@mapNotNull null
                val sources = com.cloudstreampc.player.LiveTv.sources(request, chosen)
                println("  [${response.name}] sources : ${sources.joinToString { it.label }}")
                response.name to sources
            }
        }
    }
}
