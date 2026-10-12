package com.cloudstreampc

import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.cloudstreampc.player.DiscordPresence
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.player.PlayRequest
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Rich Presence frames, sent to a fake Discord listening on /tmp/discord-ipc-N (Linux and macOS only). */
class DiscordPresenceTest {
    @Test
    fun sendsHandshakeThenActivity() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        if (System.getenv("XDG_RUNTIME_DIR") != null || System.getenv("TMPDIR") != null) return@runBlocking
        val socket = (0..9).map { File("/tmp/discord-ipc-$it") }.firstOrNull { !it.exists() } ?: return@runBlocking
        val received = LinkedBlockingQueue<Pair<Int, String>>()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.path)) }
        val thread = Thread {
            runCatching {
                server.accept().use { channel ->
                    val input = DataInputStream(Channels.newInputStream(channel))
                    val output = Channels.newOutputStream(channel)
                    repeat(2) {
                        val header = ByteArray(8).also { input.readFully(it) }
                        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val opcode = buffer.int
                        val payload = String(ByteArray(buffer.int).also { input.readFully(it) })
                        received += opcode to payload
                        output.write(DiscordPresence.frame(1, mapOf("evt" to if (opcode == 0) "READY" else null, "cmd" to "SET_ACTIVITY")))
                        output.flush()
                    }
                }
            }
        }.apply { start() }
        try {
            val before = Store.settings
            Store.updateSettings { it.copy(discordPresence = true, discordAppId = "123456789") }
            val api = object : MainAPI() { override var name = "Test" }
            val episodes = listOf(api.newEpisode("ep1") { season = 1; episode = 3; name = "Le piège" })
            val response = api.newTvSeriesLoadResponse("MobLand", "https://site/mobland", TvType.TvSeries, episodes) { posterUrl = "https://img/p.jpg" }
            val queue = PlayQueue.build(response, episodes)
            DiscordPresence.watching(PlayRequest(api, response, queue, 0), startedAt = 1_700_000_000_000)

            val handshake = assertNotNull(received.poll(5, TimeUnit.SECONDS))
            assertEquals(0, handshake.first)
            assertEquals("123456789", mapper.readTree(handshake.second).path("client_id").asText())
            val activity = assertNotNull(received.poll(5, TimeUnit.SECONDS))
            val json = mapper.readTree(activity.second)
            assertEquals("SET_ACTIVITY", json.path("cmd").asText())
            val a = json.path("args").path("activity")
            assertEquals("MobLand", a.path("details").asText())
            assertEquals("S1·E3", a.path("state").asText())
            assertEquals(1_700_000_000L, a.path("timestamps").path("start").asLong())
            assertEquals("https://img/p.jpg", a.path("assets").path("large_image").asText())
            Store.updateSettings { before }
        } finally {
            thread.join(2000)
            server.close()
            socket.delete()
        }
    }
}
