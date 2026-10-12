package com.cloudstreampc.cast

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.compactJson
import com.cloudstreampc.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** A Chromecast (or a TV with Chromecast built in) found on the local network. */
data class ChromecastDevice(val name: String, val host: String, val port: Int = 8009)

/**
 * The CastMessage of the cast protocol: a tiny protobuf with string fields, encoded by hand
 * (no protobuf library, which would clash with the one of the CloudStream library).
 */
internal data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun field(number: Int, text: String) {
            val bytes = text.toByteArray()
            varint(((number shl 3) or 2).toLong())
            varint(bytes.size.toLong())
            out.write(bytes)
        }
        varint((1 shl 3).toLong()); varint(0) // protocol_version = CASTV2_1_0
        field(2, source)
        field(3, destination)
        field(4, namespace)
        varint((5 shl 3).toLong()); varint(0) // payload_type = STRING
        field(6, payload)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            var i = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = bytes[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            val strings = mutableMapOf<Int, String>()
            while (i < bytes.size) {
                val key = varint().toInt()
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val length = varint().toInt()
                        strings[key ushr 3] = String(bytes, i, length)
                        i += length
                    }
                    else -> error("Champ protobuf inattendu")
                }
            }
            return CastMessage(strings[2].orEmpty(), strings[3].orEmpty(), strings[4].orEmpty(), strings[6].orEmpty())
        }
    }
}

/** One connection to a Chromecast, playing a video with the Default Media Receiver. */
class ChromecastSession(val device: ChromecastDevice) : AutoCloseable {
    companion object {
        private const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val MEDIA = "urn:x-cast:com.google.cast.media"
        private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        private const val SENDER = "sender-cloudstreampc"

        /** Chromecasts use self-signed certificates. */
        private val trustAll = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }

        /** Chromecasts announce themselves with mDNS (_googlecast._tcp). */
        fun discover(timeoutMs: Long = 3000): List<ChromecastDevice> = runCatching {
            JmDNS.create().use { dns ->
                dns.list("_googlecast._tcp.local.", timeoutMs).mapNotNull { info ->
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return@mapNotNull null
                    ChromecastDevice(info.getPropertyString("fn") ?: info.name, host, info.port)
                }.distinctBy { it.host }
            }
        }.onFailure { Logger.info("Recherche Chromecast : ${it.message}") }.getOrDefault(emptyList())
    }

    private val socket: SSLSocket = (trustAll.socketFactory.createSocket() as SSLSocket).apply {
        connect(InetSocketAddress(device.host, device.port), 5000)
        startHandshake()
    }
    private val output = DataOutputStream(socket.outputStream)
    private val input = DataInputStream(socket.inputStream)
    private val requestIds = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableFuture<JsonNode>>()
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile var mediaSessionId: Int? = null
        private set
    @Volatile private var closed = false

    /** Last media status: playing, paused, buffering, idle. */
    @Volatile var playerState: String = "IDLE"
        private set

    init {
        Thread(::readLoop, "chromecast-read").apply { isDaemon = true }.start()
        Thread(::heartbeat, "chromecast-ping").apply { isDaemon = true }.start()
        send("receiver-0", CONNECTION, mapOf("type" to "CONNECT"))
    }

    @Synchronized
    private fun send(destination: String, namespace: String, payload: Map<String, Any?>) {
        val bytes = CastMessage(SENDER, destination, namespace, compactJson(payload)).encode()
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun request(destination: String, namespace: String, payload: Map<String, Any?>): JsonNode {
        val id = requestIds.getAndIncrement()
        val future = CompletableFuture<JsonNode>()
        waiting[id] = future
        send(destination, namespace, payload + ("requestId" to id))
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val length = input.readInt()
                val message = CastMessage.decode(ByteArray(length).also { input.readFully(it) })
                val json = runCatching { mapper.readTree(message.payload) }.getOrNull() ?: continue
                when (json.path("type").asText()) {
                    "PING" -> send(message.source, HEARTBEAT, mapOf("type" to "PONG"))
                    "MEDIA_STATUS" -> json.path("status").firstOrNull()?.let { status ->
                        mediaSessionId = status.path("mediaSessionId").asInt()
                        playerState = status.path("playerState").asText(playerState)
                    }
                }
                json.path("requestId").asInt(0).takeIf { it > 0 }?.let { waiting.remove(it)?.complete(json) }
            }
        } catch (t: Exception) {
            if (!closed) Logger.info("Chromecast ${device.name} déconnecté : ${t.message}")
        } finally {
            closed = true
            waiting.values.forEach { it.completeExceptionally(IllegalStateException("Chromecast déconnecté")) }
        }
    }

    private fun heartbeat() {
        while (!closed) {
            Thread.sleep(5000)
            runCatching { send("receiver-0", HEARTBEAT, mapOf("type" to "PING")) }
        }
    }

    /** Launches the Default Media Receiver and loads [url]. */
    fun load(url: String, contentType: String, title: String, posterUrl: String?, startAt: Double = 0.0) {
        val status = request("receiver-0", RECEIVER, mapOf("type" to "LAUNCH", "appId" to DEFAULT_MEDIA_RECEIVER))
        val app = status.path("status").path("applications").firstOrNull { it.path("appId").asText() == DEFAULT_MEDIA_RECEIVER }
            ?: error("Le Chromecast n'a pas lancé le lecteur.")
        transportId = app.path("transportId").asText()
        sessionId = app.path("sessionId").asText()
        send(transportId!!, CONNECTION, mapOf("type" to "CONNECT"))
        val media = mapOf(
            "contentId" to url,
            "contentUrl" to url,
            "contentType" to contentType,
            "streamType" to "BUFFERED",
            "metadata" to mapOf(
                "metadataType" to 0,
                "title" to title,
                "images" to listOfNotNull(posterUrl?.let { mapOf("url" to it) }),
            ),
        )
        val answer = request(transportId!!, MEDIA, mapOf("type" to "LOAD", "media" to media, "autoplay" to true, "currentTime" to startAt))
        if (answer.path("type").asText() == "LOAD_FAILED") error("Le Chromecast n'a pas pu lire cette vidéo.")
    }

    private fun mediaCommand(type: String) {
        val transport = transportId ?: return
        val media = mediaSessionId ?: return
        send(transport, MEDIA, mapOf("type" to type, "mediaSessionId" to media, "requestId" to requestIds.getAndIncrement()))
    }

    fun pause() = mediaCommand("PAUSE")
    fun play() = mediaCommand("PLAY")

    fun stop() {
        sessionId?.let { runCatching { send("receiver-0", RECEIVER, mapOf("type" to "STOP", "sessionId" to it, "requestId" to requestIds.getAndIncrement())) } }
    }

    val isOpen get() = !closed

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }
}
