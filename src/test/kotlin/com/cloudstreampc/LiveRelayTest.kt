package com.cloudstreampc

import com.cloudstreampc.player.LiveRelay
import com.cloudstreampc.player.LiveSource
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live relay against a misbehaving fake provider: streams that drop (mid-packet), stall,
 * answer 503, HLS playlists, and a one-connection limit.
 */
class LiveRelayTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val connections = AtomicInteger()
    private val hlsStart = System.currentTimeMillis()

    /** A 188-byte MPEG-TS packet carrying [stream] and [counter] (to check order and integrity). */
    private fun packet(stream: Int, counter: Int) = ByteArray(188).also {
        it[0] = 0x47
        it[1] = stream.toByte()
        for (i in 0 until 4) it[2 + i] = (counter shr (8 * (3 - i))).toByte()
        for (i in 6 until 188) it[i] = (i and 0x3F).toByte()
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            connections.incrementAndGet()
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                when {
                    path == "/dead.ts" -> exchange.sendResponseHeaders(503, -1)
                    path == "/garbage" -> {
                        val body = "<html>not a video</html>".toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                    // 400 packets, then the connection is cut in the middle of a packet.
                    path == "/drop.ts" -> {
                        exchange.sendResponseHeaders(200, 0)
                        val out = exchange.responseBody
                        for (i in 0 until 400) {
                            out.write(packet(1, i))
                            if (i % 50 == 0) { out.flush(); Thread.sleep(5) }
                        }
                        out.write(packet(1, 400), 0, 77)
                        out.flush()
                    }
                    // A few packets then nothing: stuck.
                    path == "/stall.ts" -> {
                        exchange.sendResponseHeaders(200, 0)
                        repeat(20) { exchange.responseBody.write(packet(2, it)) }
                        exchange.responseBody.flush()
                        Thread.sleep(30_000)
                    }
                    path == "/good.ts" -> {
                        exchange.sendResponseHeaders(200, 0)
                        val out = exchange.responseBody
                        var i = 0
                        while (true) {
                            out.write(packet(3, i++))
                            if (i % 100 == 0) { out.flush(); Thread.sleep(10) }
                        }
                    }
                    // Live HLS: a new 2-second segment every 2 seconds, window of 5.
                    path == "/live/master.m3u8" -> text(exchange, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh.m3u8\n")
                    path == "/live/high.m3u8" -> {
                        val last = ((System.currentTimeMillis() - hlsStart) / 2000).toInt() + 5
                        val first = last - 4
                        text(exchange, buildString {
                            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$first\n")
                            for (n in first..last) append("#EXTINF:2.0,\nseg$n.ts\n")
                        })
                    }
                    path.startsWith("/live/seg") -> {
                        val n = path.removePrefix("/live/seg").removeSuffix(".ts").toInt()
                        val body = ByteArrayOutputStream().apply { repeat(50) { write(packet(4, n * 50 + it)) } }.toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                    else -> exchange.sendResponseHeaders(404, -1)
                }
            } catch (_: Exception) {
            } finally {
                active.decrementAndGet()
                runCatching { exchange.close() }
            }
        }
        server.start()
        LiveRelay.stallTimeoutMs = 1_500
        LiveRelay.backoffMs = listOf(50L, 100L, 200L, 400L)
        LiveRelay.attemptsPerSource = 2
        LiveRelay.maxOpenConnections.set(0)
    }

    private fun text(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    @AfterTest
    fun stop() {
        LiveRelay.close()
        server.stop(0)
    }

    /** Reads the relay like the player does, for [ms] or [bytes]. */
    private fun read(url: String, ms: Long, bytes: Int = Int.MAX_VALUE): ByteArray {
        val connection = URL(url).openConnection()
        connection.readTimeout = 20_000
        val input: InputStream = connection.getInputStream()
        val out = ByteArrayOutputStream()
        val end = System.currentTimeMillis() + ms
        val buffer = ByteArray(16 * 1024)
        while (System.currentTimeMillis() < end && out.size() < bytes) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        input.close()
        return out.toByteArray()
    }

    /** Every 188 bytes a sync byte, and every packet whole: what the player needs to keep decoding. */
    private fun assertCleanTs(data: ByteArray): List<Pair<Int, Int>> {
        assertTrue(data.size >= 188, "trop peu de données : ${data.size}")
        val usable = data.size - data.size % 188
        return (0 until usable step 188).map { offset ->
            assertEquals(0x47.toByte(), data[offset], "paquet désaligné à $offset")
            for (i in 6 until 188) assertEquals((i and 0x3F).toByte(), data[offset + i], "paquet abîmé à $offset")
            val counter = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (data[offset + 2 + i].toInt() and 0xFF) }
            data[offset + 1].toInt() to counter
        }
    }

    @Test
    fun reconnectsAfterADropWithoutBrokenPackets() {
        val url = LiveRelay.open(listOf(LiveSource("$base/drop.ts", label = "drop")))
        // Three connections' worth: the stream goes on across two cuts.
        val data = read(url, 15_000, bytes = 188 * 1100)
        val packets = assertCleanTs(data)
        assertTrue(packets.size >= 1100, "${packets.size} paquets")
        assertTrue(LiveRelay.reconnects >= 2, "reconnexions : ${LiveRelay.reconnects}")
        // The half packet of each cut was dropped: counters go 0..399, then start again.
        assertTrue(packets.none { it.second == 400 })
        assertEquals(1, LiveRelay.maxOpenConnections.get(), "jamais deux connexions au fournisseur")
    }

    @Test
    fun switchesToTheBackupSourceWhenOneIsDownOrStuck() {
        val url = LiveRelay.open(
            listOf(
                LiveSource("$base/dead.ts", label = "en panne"),
                LiveSource("$base/stall.ts", label = "figé"),
                LiveSource("$base/good.ts", label = "secours"),
            ),
        )
        val started = System.currentTimeMillis()
        val data = read(url, 20_000, bytes = 188 * 3000)
        val packets = assertCleanTs(data)
        assertTrue(packets.count { it.first == 3 } > 2000, "le flux de secours est lu")
        assertEquals("secours", LiveRelay.currentSource?.label)
        assertTrue(System.currentTimeMillis() - started < 15_000, "bascule en moins de 15 s")
        assertEquals(1, LiveRelay.maxOpenConnections.get(), "jamais deux connexions au fournisseur")
    }

    @Test
    fun turnsLiveHlsIntoOneContinuousStream() {
        val url = LiveRelay.open(listOf(LiveSource("$base/live/master.m3u8", label = "hls")))
        val data = read(url, 7_000)
        val packets = assertCleanTs(data)
        assertTrue(packets.all { it.first == 4 })
        // Several segments in a row, in order, starting a little behind the live edge.
        val counters = packets.map { it.second }
        assertEquals(counters.sorted(), counters)
        assertTrue(counters.size >= 150 * 2, "${counters.size} paquets")
        assertEquals(1, LiveRelay.maxOpenConnections.get())
    }

    @Test
    fun givesUpOnWhatIsNotVideo() {
        val url = LiveRelay.open(listOf(LiveSource("$base/garbage", label = "page")))
        val data = read(url, 5_000)
        assertEquals(0, data.size)
        assertTrue(LiveRelay.lastGiveUpUnreadable)
    }

    @Test
    fun aNewChannelClosesThePreviousConnection() {
        val first = LiveRelay.open(listOf(LiveSource("$base/good.ts", label = "un")))
        val reader = Thread { runCatching { read(first, 10_000) } }.apply { start() }
        Thread.sleep(800)
        assertEquals(1, LiveRelay.openConnections.get())
        val second = LiveRelay.open(listOf(LiveSource("$base/good.ts", label = "deux")))
        Thread.sleep(500)
        assertTrue(LiveRelay.openConnections.get() <= 1, "l'ancienne chaîne est fermée")
        val data = read(second, 2_000)
        assertCleanTs(data)
        reader.join(12_000)
        assertEquals(1, LiveRelay.maxOpenConnections.get(), "jamais deux connexions au fournisseur")
    }
}
