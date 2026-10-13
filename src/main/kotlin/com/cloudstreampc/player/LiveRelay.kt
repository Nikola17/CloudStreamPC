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
    /** No byte for this long: the provider's stream is stuck, reconnect (upper bound, see [minStallMs]). */
    internal var stallTimeoutMs = 6_000L
    /**
     * A steady stream is declared stuck much sooner: after 2.5 times the longest pause seen between
     * its packets, at least this long. Bursty providers (data every 2 s) get more time automatically.
     */
    internal var minStallMs = 2_500L
    /** "Too many connections" after a drop: the provider frees our previous connection within seconds. */
    internal var busyRetryMs = 1_500L
    /**
     * A source that dropped this many times within [flakyWindowMs] gives way to the next one. Changing source
     * costs a short freeze (another stream to join), so only sources that drop really often are left: measured
     * on a real provider, one dropping every 15-30 s gains from it, one dropping every 1-2 minutes doesn't.
     */
    internal var flakyDrops = 5
    internal var flakyWindowMs = 120_000L
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
        @Volatile var upstreamOwner: Reader? = null
        val reconnects = AtomicInteger()
        @Volatile var sourceIndex = 0
        /** Until when each source answered "busy" (the provider still counts our previous connection). */
        val busyUntil = LongArray(sources.size)
        /** The player connection being fed; a new one (the player reconnected) retires the old. */
        @Volatile var reader: Reader? = null
        /** Stall watch of the stream being copied: last data, and how long a silence may last. */
        @Volatile var reading = false
        @Volatile var lastReadAt = 0L
        @Volatile var stallLimitMs = 0L

        fun close() {
            closed = true
            runCatching { upstream?.close() }
        }
    }

    private class Reader(val aligner: TsAligner) {
        @Volatile var stale = false
    }

    /** The reader the current thread feeds (checked before each provider request). */
    private val threadReader = ThreadLocal<Reader?>()

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

    /** Closes a provider stream that went silent for longer than its own rhythm allows. */
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "live-watchdog").apply { isDaemon = true } }.apply {
        scheduleWithFixedDelay({
            val session = current ?: return@scheduleWithFixedDelay
            // Between two provider connections, empty packets keep the player's connection alive:
            // it plays from its buffer and never drops the relay (a dropped player connection is
            // slower to come back, and briefly doubled the provider connections).
            session.reader?.takeIf { !it.stale && !session.closed }?.let { runCatching { it.aligner.keepAlive(1_000) } }
            if (!session.reading || session.closed) return@scheduleWithFixedDelay
            val silent = System.currentTimeMillis() - session.lastReadAt
            if (silent > session.stallLimitMs) {
                Logger.info("Direct : flux figé depuis ${silent} ms, reconnexion")
                session.reading = false
                runCatching { session.upstream?.close() }
            }
        }, 200, 200, TimeUnit.MILLISECONDS)
    }

    /** Built from the extensions' client, so the DNS chosen in the settings (DNS over HTTPS) applies. */
    private var clientFor: Pair<OkHttpClient, OkHttpClient>? = null
    private val baseClient: OkHttpClient
        @Synchronized get() {
            val shared = app.baseClient
            clientFor?.takeIf { it.first === shared }?.let { return it.second }
            // No connection reuse: after a frozen stream is dropped, a reused connection can still carry the rest of the
            // old stream ("Unexpected status line" with video bytes, seen with a real IPTV provider). A new connection
            // per request costs a few milliseconds.
            return shared.newBuilder().connectTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(true).cache(null)
                .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
                .also { clientFor = shared to it }
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
        exchange.responseBody.use { out ->
            val reader = Reader(TsAligner(out))
            session.reader?.let { old ->
                old.stale = true
                runCatching { session.upstream?.close() }
            }
            session.reader = reader
            threadReader.set(reader)
            try {
                relay(session, reader)
            } finally {
                threadReader.remove()
                if (session.reader === reader) session.reader = null
            }
        }
    }

    /** Feeds the player until it disconnects or the channel is closed. */
    private fun relay(session: Session, reader: Reader) {
        val aligner = reader.aligner
        var failures = 0
        var lastData = System.currentTimeMillis()
        val dead = mutableSetOf<Int>()
        // Times each source dropped lately: one that keeps dropping gives way to the next one.
        val drops = mutableMapOf<Int, ArrayDeque<Long>>()
        val sessionStart = System.currentTimeMillis()
        // Changing channel: give the provider a moment to see the previous connection close.
        sleepUnlessClosed(session, 300 - (sessionStart - lastClose))
        while (!session.closed && !reader.stale) {
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
                // The provider still counts our previous connection (after a drop or a channel change):
                // another free source right away, otherwise this one again in a moment. Not a failure.
                closeOwnUpstream(session)
                val now = System.currentTimeMillis()
                session.busyUntil[index] = now + busyRetryMs
                if (now - lastData < giveUpAfterMs) {
                    val free = (1..session.sources.size).map { (index + it) % session.sources.size }
                        .firstOrNull { it !in dead && it != index && session.busyUntil[it] <= now }
                    if (free != null) {
                        Logger.info("Direct : ${source.label} occupé (${e.message}), essai de ${session.sources[free].label}")
                        session.sourceIndex = free
                    } else {
                        val wait = (session.busyUntil.filterIndexed { i, _ -> i !in dead }.minOrNull() ?: now) - now
                        Logger.info("Direct : ${source.label} occupé (${e.message}), nouvel essai")
                        sleepUnlessClosed(session, wait.coerceIn(300, busyRetryMs))
                    }
                    continue
                }
                Logger.info("Direct : coupure de ${source.label} (${e.message})")
            } catch (e: Exception) {
                if (session.closed) return
                Logger.info("Direct : coupure de ${source.label} (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                closeOwnUpstream(session)
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
            val now = System.currentTimeMillis()
            val recent = drops.getOrPut(index) { ArrayDeque() }.apply { addLast(now); while (now - first() > flakyWindowMs) removeFirst() }
            // Plays, then freezes again and again (seen with a real IPTV provider, every 15 to 30 s): the
            // reconnections are hidden by the buffer, but not always. A copy of the channel is often steadier.
            val flaky = session.sources.size > 1 && recent.size >= flakyDrops
            if (flaky) Logger.info("Direct : ${source.label} se coupe souvent (${recent.size} fois en ${flakyWindowMs / 1000} s), essai d'une autre source")
            val switch = index in dead || failures >= attemptsPerSource || flaky
            if (switch) {
                recent.clear()
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

    /** Closes the provider connection of this thread's reader (not the one of a newer reader). */
    private fun closeOwnUpstream(session: Session) {
        val mine = threadReader.get()
        if (mine == null || session.upstreamOwner === mine) {
            session.upstream?.let { runCatching { it.close() } }
            session.upstream = null
        }
    }

    private fun sleepUnlessClosed(session: Session, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!session.closed && threadReader.get()?.stale != true && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    private fun get(session: Session, url: String, headers: Map<String, String>): Response {
        if (session.closed || threadReader.get()?.stale == true) throw ClientGone()
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
        session.upstreamOwner = threadReader.get()
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
        val started = System.currentTimeMillis()
        var longestGap = 0L
        session.lastReadAt = started
        session.stallLimitMs = stallTimeoutMs
        session.reading = true
        try {
            while (!session.closed) {
                val n = input.read(buffer)
                if (n < 0) return
                val now = System.currentTimeMillis()
                // The provider's own rhythm, once its connection burst is over.
                if (now - started > 3_000) {
                    longestGap = maxOf(longestGap, now - session.lastReadAt)
                    session.stallLimitMs = (longestGap * 5 / 2).coerceIn(minOf(minStallMs, stallTimeoutMs), stallTimeoutMs)
                }
                session.lastReadAt = now
                if (n > 0) aligner.write(buffer, n)
            }
        } finally {
            session.reading = false
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
                aligner.dropPartial()
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

        /** End of an HLS segment: a packet it left unfinished is not completed by the next one. */
        fun dropPartial() {
            pendingSize = 0
        }

        /** The connection dropped: whatever packet was half received is dropped with it. */
        fun reset() {
            pendingSize = 0
            // Providers start a new connection with the last seconds they sent: skip what the player
            // already has, otherwise the picture jumps back a few seconds after every reconnection.
            if (lastPcr >= 0) {
                skipUntilPcr = lastPcr
                skippedBytes = 0
            }
        }

        /** Program clock (90 kHz) of the last packet passed on, and its stream; -1 when unknown. */
        private var lastPcr = -1L
        private var pcrPid = -1
        private var skipUntilPcr = -1L
        private var skippedBytes = 0L
        /** Packets dropped because the player already had them (tests and logs). */
        var duplicateBytes = 0L
            private set

        private fun emit(bytes: ByteArray, offset: Int, length: Int) {
            var runStart = offset
            var i = offset
            val end = offset + length
            while (i < end) {
                val pcr = pcrOf(bytes, i)
                if (skipUntilPcr >= 0) {
                    val pid = pidOf(bytes, i)
                    val resume = when {
                        pcr < 0 || (pcrPid >= 0 && pid != pcrPid) -> false
                        else -> {
                            val ahead = (pcr - skipUntilPcr).mod(PCR_WRAP)
                            // Newer than what was sent, or another clock altogether (backup source): go on.
                            ahead in 1 until MAX_JUMP || ahead >= MAX_JUMP && PCR_WRAP - ahead >= MAX_JUMP
                        }
                    }
                    if (resume || skippedBytes > MAX_SKIP) {
                        skipUntilPcr = -1
                        runStart = i
                    } else {
                        skippedBytes += PACKET
                        duplicateBytes += PACKET
                        i += PACKET
                        runStart = i
                        continue
                    }
                }
                if (pcr >= 0) {
                    lastPcr = pcr
                    pcrPid = pidOf(bytes, i)
                }
                i += PACKET
            }
            if (end > runStart) send(bytes, runStart, end - runStart)
        }

        @Volatile private var lastSent = System.currentTimeMillis()

        private fun send(bytes: ByteArray, offset: Int, length: Int) {
            synchronized(out) {
                try {
                    out.write(bytes, offset, length)
                    out.flush()
                } catch (e: IOException) {
                    throw ClientGone()
                }
                lastSent = System.currentTimeMillis()
            }
            written += length
        }

        /** An MPEG-TS null packet (ignored by players) when nothing was sent for [idleMs]. */
        fun keepAlive(idleMs: Long) {
            if (System.currentTimeMillis() - lastSent < idleMs) return
            synchronized(out) {
                try {
                    out.write(NULL_PACKET)
                    out.flush()
                } catch (_: IOException) {
                }
                lastSent = System.currentTimeMillis()
            }
        }

        companion object {
            const val PACKET = 188
            const val SYNC: Byte = 0x47
            private val NULL_PACKET = ByteArray(PACKET) { 0xFF.toByte() }.also { it[0] = SYNC; it[1] = 0x1F; it[2] = 0xFF.toByte(); it[3] = 0x10 }
            private const val PCR_WRAP = 1L shl 33
            /** Clocks further apart than 30 s are not the same broadcast. */
            private const val MAX_JUMP = 30L * 90_000
            private const val MAX_SKIP = 32L * 1024 * 1024

            private fun pidOf(b: ByteArray, i: Int) = ((b[i + 1].toInt() and 0x1F) shl 8) or (b[i + 2].toInt() and 0xFF)

            /** PCR base (90 kHz) carried by the packet at [i], or -1. */
            internal fun pcrOf(b: ByteArray, i: Int): Long {
                val adaptation = (b[i + 3].toInt() shr 4) and 0x3
                if (adaptation != 2 && adaptation != 3) return -1
                val length = b[i + 4].toInt() and 0xFF
                if (length < 7 || (b[i + 5].toInt() and 0x10) == 0) return -1
                return ((b[i + 6].toLong() and 0xFF) shl 25) or ((b[i + 7].toLong() and 0xFF) shl 17) or
                    ((b[i + 8].toLong() and 0xFF) shl 9) or ((b[i + 9].toLong() and 0xFF) shl 1) or ((b[i + 10].toLong() and 0xFF) shr 7)
            }

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
