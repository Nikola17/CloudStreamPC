package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.download.Hls
import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One way to get a live channel: an address and the headers it needs. */
data class LiveSource(val url: String, val headers: Map<String, String> = emptyMap(), val label: String = url)

/**
 * Keeps live TV going through network trouble. The player reads one endless MPEG-TS stream from
 * this local relay; the relay reads the channel from the provider and, when the provider's stream
 * drops, stalls or answers an error, reconnects at once (then switches to the next source: other
 * format, other account) and carries on where it was. The player never sees the cut: at worst it
 * plays from its own buffer for the second it takes.
 *
 * HLS channels are read segment by segment and passed on as the same continuous MPEG-TS stream, so
 * switching between a `.ts` and a `.m3u8` source is invisible too.
 */
object LiveRelay {
    /** No byte for this long: the provider's stream is stuck, reconnect. */
    internal var stallTimeoutMs = 6_000L
    /** Pauses between reconnections that brought nothing, growing then capped. */
    internal var backoffMs = listOf(300L, 1_000L, 2_000L, 4_000L)
    /** Failed attempts in a row on one source before trying the next. */
    internal var attemptsPerSource = 3
    /** Nothing from any source for this long: give up (the player then shows the end). */
    internal var giveUpAfterMs = 90_000L

    /** The last channel given up had only sources the relay cannot read (the player should read them itself). */
    @Volatile var lastGiveUpUnreadable = false
        private set

    /** "Reconnexion…", "Source de secours : …" for the player's screen. */
    @Volatile var onStatus: (String) -> Unit = {}

    private class Session(val id: String, val sources: List<LiveSource>) {
        @Volatile var closed = false
        @Volatile var upstream: Response? = null
        val reconnects = AtomicInteger()
        @Volatile var sourceIndex = 0

        fun close() {
            closed = true
            runCatching { upstream?.close() }
        }
    }

    private class NotRelayable(message: String) : IOException(message)

    /** "Too many connections" (403, 429…): the provider has not yet seen the previous channel close. */
    private class Busy(code: Int) : IOException("HTTP $code (connexions)")

    @Volatile private var current: Session? = null
    private var server: HttpServer? = null

    /** Provider connections open right now (never more than one: most IPTV plans allow only one). */
    internal val openConnections = AtomicInteger()
    /** Most connections ever open at once (tests check it stays at one). */
    internal val maxOpenConnections = AtomicInteger()

    /** Times the current channel had to reconnect, and the source it is on (for tests and the logs). */
    val reconnects: Int get() = current?.reconnects?.get() ?: 0
    val currentSource: LiveSource? get() = current?.let { it.sources.getOrNull(it.sourceIndex) }

    private val baseClient: OkHttpClient by lazy {
        app.baseClient.newBuilder().connectTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(true).cache(null).build()
    }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { return it.address.port }
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.executor = Executors.newCachedThreadPool { Thread(it, "live-relay").apply { isDaemon = true } }
        created.createContext("/live/") { exchange ->
            runCatching { serve(exchange) }.onFailure { Logger.info("Relais direct : ${it.message}") }
            runCatching { exchange.close() }
        }
        created.start()
        server = created
        return created.address.port
    }

    /**
     * Address the player opens for this channel. The previous channel is closed first, so only one
     * connection to the provider exists at any time.
     */
    fun open(sources: List<LiveSource>): String {
        require(sources.isNotEmpty())
        val port = ensureStarted()
        close()
        val session = Session(UUID.randomUUID().toString().replace("-", ""), sources)
        lastGiveUpUnreadable = false
        current = session
        return "http://127.0.0.1:$port/live/${session.id}.ts"
    }

    /** Stops the current channel and its provider connection. */
    fun close() {
        current?.let {
            it.close()
            lastClose = System.currentTimeMillis()
        }
        current = null
    }

    @Volatile private var lastClose = 0L

    private fun serve(exchange: HttpExchange) {
        val id = exchange.requestURI.path.substringAfterLast('/').substringBefore('.')
        val session = current?.takeIf { it.id == id }
        if (session == null || session.closed) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        exchange.responseHeaders.add("Content-Type", "video/mp2t")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(200, -1)
            return
        }
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out -> relay(session, out) }
    }

    /** Feeds the player until it disconnects or the channel is closed. */
    private fun relay(session: Session, out: OutputStream) {
        val aligner = TsAligner(out)
        var failures = 0
        var lastData = System.currentTimeMillis()
        val dead = mutableSetOf<Int>()
        val sessionStart = System.currentTimeMillis()
        // Changing channel: give the provider a moment to see the previous connection close.
        sleepUnlessClosed(session, 300 - (sessionStart - lastClose))
        while (!session.closed) {
            val index = session.sourceIndex
            val source = session.sources[index]
            val before = aligner.written
            val startedAt = System.currentTimeMillis()
            try {
                pump(session, source, aligner)
                // A live stream that "ends" has dropped.
                Logger.info("Direct : fin du flux de ${source.label}, reconnexion")
            } catch (e: ClientGone) {
                return
            } catch (e: NotRelayable) {
                Logger.info("Direct : ${source.label} ignoré (${e.message})")
                dead += index
            } catch (e: Busy) {
                if (session.closed) return
                // Right after changing channel, wait for the provider to free the previous connection.
                if (System.currentTimeMillis() - sessionStart < 12_000) {
                    Logger.info("Direct : ${source.label} occupé (${e.message}), nouvel essai")
                    session.upstream?.let { runCatching { it.close() } }
                    session.upstream = null
                    sleepUnlessClosed(session, 1_000)
                    continue
                }
                Logger.info("Direct : coupure de ${source.label} (${e.message})")
            } catch (e: Exception) {
                if (session.closed) return
                Logger.info("Direct : coupure de ${source.label} (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                session.upstream?.let { runCatching { it.close() } }
                session.upstream = null
            }
            if (session.closed) return
            aligner.reset()
            val got = aligner.written - before
            if (got > 0) lastData = System.currentTimeMillis()
            // Played for a while before dropping: reconnect straight away. Nothing came: count a failure.
            val healthy = got > 256 * 1024 || System.currentTimeMillis() - startedAt > 20_000 && got > 0
            failures = if (healthy) 0 else failures + 1
            session.reconnects.incrementAndGet()
            if (System.currentTimeMillis() - lastData > giveUpAfterMs || dead.size == session.sources.size) {
                lastGiveUpUnreadable = dead.size == session.sources.size
                Logger.info("Direct : aucune source ne répond, abandon")
                onStatus("Chaîne indisponible")
                return
            }
            val switch = index in dead || failures >= attemptsPerSource
            if (switch) {
                val next = (1..session.sources.size).map { (index + it) % session.sources.size }.firstOrNull { it !in dead } ?: return
                if (next != index) {
                    session.sourceIndex = next
                    onStatus("Source de secours : ${session.sources[next].label}")
                }
                failures = 0
            } else {
                onStatus("Reconnexion…")
            }
            val wait = if (healthy) backoffMs.first() else backoffMs[(failures - 1).coerceIn(0, backoffMs.lastIndex)]
            sleepUnlessClosed(session, wait)
        }
    }

    private fun sleepUnlessClosed(session: Session, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!session.closed && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    private fun get(session: Session, url: String, headers: Map<String, String>): Response {
        if (session.closed) throw ClientGone()
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        maxOpenConnections.accumulateAndGet(openConnections.incrementAndGet()) { a, b -> maxOf(a, b) }
        val response = try {
            baseClient.newBuilder().readTimeout(stallTimeoutMs, TimeUnit.MILLISECONDS).build().newCall(request).execute()
        } catch (e: Exception) {
            openConnections.decrementAndGet()
            throw e
        }
        val tracked = response.newBuilder().body(CountedBody(response.body)).build()
        // The channel was changed while this connection was being made: drop it at once.
        if (session.closed) {
            tracked.close()
            throw ClientGone()
        }
        if (!response.isSuccessful) {
            tracked.close()
            if (response.code in setOf(403, 429, 458, 509)) throw Busy(response.code)
            throw IOException("HTTP ${response.code}")
        }
        return tracked
    }

    /** Releases the connection count when the body is closed. */
    private class CountedBody(private val body: okhttp3.ResponseBody) : okhttp3.ResponseBody() {
        private var closed = false
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source() = body.source()
        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            openConnections.decrementAndGet()
            body.close()
        }
    }

    /** Reads one source until it fails: MPEG-TS straight through, HLS segment after segment. */
    private fun pump(session: Session, source: LiveSource, aligner: TsAligner) {
        val response = get(session, source.url, source.headers)
        session.upstream = response
        val input = response.body.byteStream()
        val head = ByteArray(1024)
        val n = input.readNBytes(head, 0, head.size)
        if (n <= 0) throw IOException("réponse vide")
        val text = String(head, 0, minOf(n, 16), Charsets.ISO_8859_1)
        when {
            text.startsWith("#EXTM3U") -> {
                val playlist = String(head, 0, n, Charsets.UTF_8) + input.readBytes().toString(Charsets.UTF_8)
                response.close()
                session.upstream = null
                pumpHls(session, source, playlist, response.request.url.toString(), aligner)
            }
            TsAligner.looksLikeTs(head, n) -> {
                aligner.write(head, n)
                copy(session, input, aligner)
            }
            else -> throw NotRelayable("ni MPEG-TS ni HLS")
        }
    }

    private fun copy(session: Session, input: InputStream, aligner: TsAligner) {
        val buffer = ByteArray(64 * 1024)
        while (!session.closed) {
            val n = input.read(buffer)
            if (n < 0) return
            if (n > 0) aligner.write(buffer, n)
        }
    }

    private fun pumpHls(session: Session, source: LiveSource, firstPlaylist: String, firstUrl: String, aligner: TsAligner) {
        var playlistUrl = firstUrl
        var text = firstPlaylist
        if (Hls.isMaster(text)) {
            val variant = Hls.variants(text, firstUrl).maxByOrNull { it.bandwidth } ?: throw NotRelayable("liste HLS vide")
            playlistUrl = variant.url
            text = fetchText(session, playlistUrl, source.headers)
        }
        val keys = mutableMapOf<String, ByteArray>()
        var lastSequence = -1L
        var lastProgress = System.currentTimeMillis()
        var first = true
        while (!session.closed) {
            val target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6
            val media = Hls.media(text, playlistUrl)
            if (media.initUrl != null) throw NotRelayable("segments fMP4")
            // Start a few segments back from the live edge: that is the margin against the next cut.
            val fresh = media.segments.filter { it.sequence > lastSequence }
                .let { if (first) it.takeLast(3) else it }
            first = false
            for (segment in fresh) {
                val bytes = fetchSegment(session, segment, source.headers, keys) ?: continue
                if (!TsAligner.looksLikeTs(bytes, bytes.size)) throw NotRelayable("segments non MPEG-TS")
                aligner.write(bytes, bytes.size)
                aligner.reset()
                lastSequence = segment.sequence
                lastProgress = System.currentTimeMillis()
            }
            if (text.contains("#EXT-X-ENDLIST")) return
            if (System.currentTimeMillis() - lastProgress > maxOf(3 * target * 1000, stallTimeoutMs * 2)) throw IOException("liste HLS figée")
            sleepUnlessClosed(session, if (fresh.isEmpty()) target * 500 else 300)
            text = fetchText(session, playlistUrl, source.headers)
        }
    }

    private fun fetchText(session: Session, url: String, headers: Map<String, String>): String =
        get(session, url, headers).use { it.body.string() }

    /** A segment, tried three times; null when it cannot be had (it is skipped, the stream goes on). */
    private fun fetchSegment(session: Session, segment: Hls.Segment, headers: Map<String, String>, keys: MutableMap<String, ByteArray>): ByteArray? {
        repeat(3) { attempt ->
            if (session.closed) throw ClientGone()
            try {
                var bytes = get(session, segment.url, headers).use { it.body.bytes() }
                val key = segment.key
                if (key != null && key.method == "AES-128" && key.uri != null) {
                    val keyBytes = keys.getOrPut(key.uri) { get(session, key.uri, headers).use { it.body.bytes() } }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(Hls.ivFor(segment)))
                    bytes = cipher.doFinal(bytes)
                } else if (key != null) {
                    throw NotRelayable("chiffrement ${key.method}")
                }
                return bytes
            } catch (e: NotRelayable) {
                throw e
            } catch (e: ClientGone) {
                throw e
            } catch (e: Exception) {
                if (attempt == 2) Logger.info("Direct : segment ${segment.sequence} perdu (${e.message})")
                sleepUnlessClosed(session, 300L * (attempt + 1))
            }
        }
        return null
    }

    internal class ClientGone : IOException("lecteur déconnecté")

    /**
     * Passes on whole 188-byte MPEG-TS packets only, realigned on the 0x47 sync byte: a packet cut by
     * a dropped connection is thrown away instead of corrupting the picture after the reconnection.
     */
    internal class TsAligner(private val out: OutputStream) {
        private val pending = ByteArray(PACKET * 2)
        private var pendingSize = 0
        var written = 0L
            private set

        fun write(bytes: ByteArray, length: Int) {
            var offset = 0
            // Complete the packet started by the previous read.
            if (pendingSize > 0) {
                val need = PACKET - pendingSize
                val take = minOf(need, length)
                System.arraycopy(bytes, 0, pending, pendingSize, take)
                pendingSize += take
                offset = take
                if (pendingSize < PACKET) return
                emit(pending, 0, PACKET)
                pendingSize = 0
            }
            var start = offset
            while (start < length) {
                if (bytes[start] != SYNC) {
                    // Lost the rhythm: find the next place where packets line up again.
                    val sync = findSync(bytes, start, length)
                    if (sync < 0) return
                    start = sync
                }
                // Copy as many consecutive aligned packets as possible in one write.
                var end = start
                while (end + PACKET <= length && bytes[end] == SYNC) end += PACKET
                if (end > start) {
                    emit(bytes, start, end - start)
                    start = end
                }
                if (start < length && length - start < PACKET) {
                    if (bytes[start] == SYNC) {
                        pendingSize = length - start
                        System.arraycopy(bytes, start, pending, 0, pendingSize)
                    }
                    return
                }
            }
        }

        /** The connection dropped: whatever packet was half received is dropped with it. */
        fun reset() {
            pendingSize = 0
        }

        private fun emit(bytes: ByteArray, offset: Int, length: Int) {
            try {
                out.write(bytes, offset, length)
                out.flush()
            } catch (e: IOException) {
                throw ClientGone()
            }
            written += length
        }

        companion object {
            const val PACKET = 188
            const val SYNC: Byte = 0x47

            private fun findSync(bytes: ByteArray, from: Int, length: Int): Int {
                for (i in from until length) {
                    if (bytes[i] != SYNC) continue
                    // Two more sync bytes one and two packets later, or the end of the data.
                    if ((i + PACKET >= length || bytes[i + PACKET] == SYNC) && (i + 2 * PACKET >= length || bytes[i + 2 * PACKET] == SYNC)) return i
                }
                return -1
            }

            fun looksLikeTs(bytes: ByteArray, length: Int): Boolean {
                if (length < PACKET) return length > 0 && bytes[0] == SYNC
                val start = (0 until minOf(length, PACKET)).firstOrNull { bytes[it] == SYNC } ?: return false
                return start + PACKET >= length || bytes[start + PACKET] == SYNC
            }
        }
    }
}
