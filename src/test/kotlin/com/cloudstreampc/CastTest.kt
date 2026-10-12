package com.cloudstreampc

import com.cloudstreampc.cast.CastMessage
import com.cloudstreampc.cast.CastProxy
import com.cloudstreampc.cast.Dlna
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Casting: Chromecast messages, the proxy that adds the hosts' headers, and a fake DLNA TV. */
class CastTest {
    private lateinit var server: HttpServer
    private val soapActions = CopyOnWriteArrayList<String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val http = HttpClient.newHttpClient()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (code, type, body) = when {
                // A host that refuses requests without its Referer.
                path.startsWith("/host/") && exchange.requestHeaders.getFirst("Referer") != "https://site.example/" ->
                    Triple(403, "text/plain", "forbidden")
                path == "/host/master.m3u8" -> Triple(200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nseg1.ts\nhttps://cdn.example/seg2.ts\n")
                path == "/host/seg1.ts" -> Triple(200, "video/mp2t", "SEGMENT-ONE")
                path == "/tv/description.xml" -> Triple(200, "text/xml", """<?xml version="1.0"?>
                    <root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>TV du salon</friendlyName>
                    <serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
                    <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/av</controlURL></service></serviceList>
                    </device></root>""")
                path == "/upnp/av" -> {
                    soapActions += exchange.requestHeaders.getFirst("SOAPACTION") + " " + exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    Triple(200, "text/xml", "<ok/>")
                }
                else -> Triple(404, "text/plain", "")
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun encodesCastMessages() {
        val message = CastMessage("sender-0", "receiver-0", "urn:x-cast:com.google.cast.media", """{"type":"LOAD","é":"ü"}""")
        val bytes = message.encode()
        assertEquals(message, CastMessage.decode(bytes))
        assertEquals(0x08, bytes[0].toInt(), "protocol_version comes first")
    }

    @Test
    fun proxySendsTheHeadersAndRewritesPlaylists() {
        val published = CastProxy.publish("$base/host/master.m3u8", mapOf("Referer" to "https://site.example/"), "127.0.0.1")
        assertTrue(published.endsWith("/index.m3u8"), published)
        val playlist = http.send(HttpRequest.newBuilder(URI(published)).build(), HttpResponse.BodyHandlers.ofString()).body()
        val lines = playlist.lines()
        assertTrue(lines[1].contains("URI=\"/v/") && lines[1].contains("key.bin"), playlist)
        assertTrue(lines[2].startsWith("/v/") && "seg1.ts" in java.net.URLDecoder.decode(lines[2], Charsets.UTF_8), playlist)
        assertTrue("cdn.example" in java.net.URLDecoder.decode(lines[3], Charsets.UTF_8))

        // The segment, fetched through the proxy, gets the Referer the host wants.
        val segment = URI(published).resolve(lines[2])
        val answer = http.send(HttpRequest.newBuilder(segment).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, answer.statusCode())
        assertEquals("SEGMENT-ONE", answer.body())
    }

    @Test
    fun drivesADlnaTv() = runBlocking {
        val tv = assertNotNull(Dlna.describe("$base/tv/description.xml"))
        assertEquals("TV du salon", tv.name)
        assertEquals("$base/upnp/av", tv.controlUrl)

        Dlna.play(tv, "http://192.168.1.10:1234/v/abc/video?x=1&y=2", "Film & co", "video/mp4")
        val actions = soapActions.map { it.substringBefore(" ").substringAfter("#").trim('"') }
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), actions)
        val setUri = soapActions[1]
        assertTrue("<CurrentURI>http://192.168.1.10:1234/v/abc/video?x=1&amp;y=2</CurrentURI>" in setUri, setUri)
        assertTrue("Film &amp;amp; co" in setUri, "the DIDL metadata is escaped twice (XML inside XML)")
    }
}

/** The cast protocol against a fake Chromecast (TLS with a throwaway certificate). */
class ChromecastSessionTest {
    @Test
    fun launchesTheMediaReceiverAndLoads() {
        val dir = java.nio.file.Files.createTempDirectory("cast").toFile()
        val store = java.io.File(dir, "ks.p12")
        val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool").path
        val made = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "cast", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=chromecast",
            "-validity", "1", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "secret", "-keypass", "secret",
        ).redirectErrorStream(true).start().waitFor()
        if (made != 0) return
        val keyStore = java.security.KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "secret".toCharArray()) }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val serverSocket = ssl.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val seen = CopyOnWriteArrayList<CastMessage>()

        val device = Thread {
            runCatching {
                serverSocket.accept().use { socket ->
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val output = java.io.DataOutputStream(socket.getOutputStream())
                    fun reply(to: CastMessage, payload: String) {
                        val bytes = CastMessage(to.destination, to.source, to.namespace, payload).encode()
                        synchronized(output) { output.writeInt(bytes.size); output.write(bytes); output.flush() }
                    }
                    while (true) {
                        val message = CastMessage.decode(ByteArray(input.readInt()).also { input.readFully(it) })
                        seen += message
                        val json = com.cloudstreampc.core.mapper.readTree(message.payload)
                        val id = json.path("requestId").asInt()
                        when (json.path("type").asText()) {
                            "LAUNCH" -> reply(message, """{"type":"RECEIVER_STATUS","requestId":$id,"status":{"applications":[
                                {"appId":"CC1AD845","sessionId":"sess-1","transportId":"web-7"}]}}""")
                            "LOAD" -> reply(message, """{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":3,"playerState":"BUFFERING"}]}""")
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        com.cloudstreampc.cast.ChromecastSession(com.cloudstreampc.cast.ChromecastDevice("Salon", "127.0.0.1", serverSocket.localPort)).use { session ->
            session.load("http://192.168.1.10:5000/v/abc/video", "video/mp4", "MobLand · S1·E1", "https://img/p.jpg", 120.0)
            assertEquals(3, session.mediaSessionId)
            assertEquals("BUFFERING", session.playerState)
            session.pause()
            Thread.sleep(300)
        }
        serverSocket.close()
        device.join(1000)

        val types = seen.map { com.cloudstreampc.core.mapper.readTree(it.payload).path("type").asText() to it.destination }
        assertEquals(listOf("CONNECT" to "receiver-0", "LAUNCH" to "receiver-0", "CONNECT" to "web-7", "LOAD" to "web-7", "PAUSE" to "web-7"), types.filter { it.first != "PING" })
        val load = com.cloudstreampc.core.mapper.readTree(seen.first { "LOAD" in it.payload }.payload)
        assertEquals("http://192.168.1.10:5000/v/abc/video", load.path("media").path("contentId").asText())
        assertEquals(120.0, load.path("currentTime").asDouble())
        assertEquals("MobLand · S1·E1", load.path("media").path("metadata").path("title").asText())
    }
}
