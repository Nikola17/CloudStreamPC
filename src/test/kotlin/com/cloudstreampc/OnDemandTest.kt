package com.cloudstreampc

import com.cloudstreampc.core.OnDemand
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDemandTest {
    private lateinit var server: HttpServer
    private val program = ByteArray(200_000) { (it * 7).toByte() }
    private var served = program

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sha = MessageDigest.getInstance("SHA-256").digest(program).joinToString("") { "%02x".format(it) }
        server.createContext("/") { exchange ->
            val name = exchange.requestURI.path.substringAfterLast('/')
            val body = if (name == "SHA2-256SUMS") {
                listOf("yt-dlp.exe", "yt-dlp_linux", "yt-dlp_linux_aarch64", "yt-dlp_macos").joinToString("") { "$sha  $it\n" }.toByteArray()
            } else {
                served
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        OnDemand.ytDlpBase = "http://127.0.0.1:${server.address.port}"
        OnDemand.dir.deleteRecursively()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun downloadsCheckedToolsOnlyOnce() = runBlocking {
        assertNull(OnDemand.installed(OnDemand.Tool.YTDLP))
        val file = OnDemand.ensure(OnDemand.Tool.YTDLP)
        assertTrue(file.readBytes().contentEquals(program))
        assertTrue(System.getProperty("os.name").lowercase().contains("win") || file.canExecute())
        assertEquals(file, OnDemand.installed(OnDemand.Tool.YTDLP))
        assertNull(OnDemand.progress)

        // A tampered download is refused and the good copy stays.
        served = program.copyOf().also { it[10] = 1 }
        assertFailsWith<IllegalStateException> { OnDemand.ensure(OnDemand.Tool.YTDLP, refresh = true) }
        assertTrue(file.readBytes().contentEquals(program))
        assertTrue(OnDemand.dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
