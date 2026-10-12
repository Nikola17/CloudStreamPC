package com.cloudstreampc

import com.cloudstreampc.core.CrashReport
import com.cloudstreampc.core.Updates
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Lot5Test {
    private lateinit var server: HttpServer
    private val installer = ByteArray(300_000) { (it % 251).toByte() }
    private var served = installer

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val name = Updates.installerNames("9.9.0").firstOrNull() ?: "CloudStreamPC-9.9.0-amd64.deb"
        val sha = MessageDigest.getInstance("SHA-256").digest(installer).joinToString("") { "%02x".format(it) }
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/latest" -> """{"tag_name":"v9.9.0","html_url":"$base/page","body":"Nouveautés","assets":[
                    {"name":"$name","browser_download_url":"$base/files/$name","size":${installer.size}},
                    {"name":"SHA256SUMS.txt","browser_download_url":"$base/files/SHA256SUMS.txt","size":100}]}""".toByteArray()
                "/files/SHA256SUMS.txt" -> "0123  autre.zip\n$sha  $name\n".toByteArray()
                "/files/$name" -> served
                else -> ByteArray(0)
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        Updates.api = "$base/latest"
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun downloadsAndChecksTheInstaller() = runBlocking {
        assertNull(Updates.check("9.9.0"))
        val release = assertNotNull(Updates.check("2.5.0"))
        assertEquals("9.9.0", release.version)
        if (release.installer == null) return@runBlocking // system without a package format we publish
        var last = 0f
        val file = Updates.download(release) { last = it }
        assertTrue(file.readBytes().contentEquals(installer))
        assertEquals(1f, last)

        // A file that does not match SHA256SUMS.txt is refused and deleted.
        served = installer.copyOf().also { it[1000] = 42 }
        assertFailsWith<IllegalStateException> { Updates.download(release) }
        assertFalse(file.exists())
    }

    @Test
    fun reportsHideSecrets() {
        val log = """
            GET http://srv.example:8080/live/jean.dupont/S3cretPass/1234.ts
            GET http://srv.example:8080/player_api.php?username=jean.dupont&password=S3cretPass&action=get_live_streams
            https://api.themoviedb.org/3/movie/1?api_key=abcdef123456&language=fr-FR
            Authorization: Bearer eyJhbGciOi.secret.token
            Erreur avec le jeton tok_ABCDEF
            http://user:pw@host/path
        """.trimIndent()
        val cleaned = CrashReport.redact(log, listOf("tok_ABCDEF", "S3cretPass"))
        listOf("jean.dupont", "S3cretPass", "abcdef123456", "eyJhbGciOi", "tok_ABCDEF", "user:pw").forEach {
            assertFalse(it in cleaned, "« $it » visible :\n$cleaned")
        }
        assertTrue("action=get_live_streams" in cleaned && "language=fr-FR" in cleaned && "/1234.ts" in cleaned)
    }

    @Test
    fun issueLinkFitsAndKeepsTheError() {
        val report = "CloudStreamPC 2.6.0\nErreur :\njava.lang.IllegalStateException: boom\n\nFin du journal :\n" +
            (1..400).joinToString("\n") { "[10:00:00] ligne de journal numéro $it avec un peu de texte" }
        val url = CrashReport.issueUrl("Plantage : boom", report)
        assertTrue(url.length <= 7_500)
        val body = URLDecoder.decode(url.substringAfter("&body="), Charsets.UTF_8)
        assertTrue("IllegalStateException: boom" in body && "numéro 400" in body && "numéro 1 " !in body)
    }
}
