package com.cloudstreampc

import com.cloudstreampc.net.ChromiumBrowser
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Extensions' WebView and Cloudflare helpers, driven through a real Chromium (skipped without one). */
class WebViewTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val browser = System.getProperty("test.browser") ?: "/opt/pw-browsers/chromium"

    @BeforeTest
    fun start() {
        System.setProperty("cloudstreampc.browser", browser)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val (code, type, body) = when (path) {
                // The video address only exists once the page's script ran.
                "/player" -> Triple(200, "text/html", """<html><head><title>Lecteur</title></head><body>
                    <script>setTimeout(() => fetch('/api/source?token=' + btoa('secret-42')), 300)</script></body></html>""")
                "/api/source" -> Triple(200, "application/json", """{"file":"$base/video.m3u8"}""")
                // A Cloudflare-like check: a script sets the clearance cookie and reloads.
                "/protected" -> if ("cf_clearance=ok" in cookie) Triple(200, "text/plain", "contenu protégé")
                else Triple(503, "text/html", """<html><body>Checking your browser…<script>
                    setTimeout(() => { document.cookie = 'cf_clearance=ok; path=/'; location.reload() }, 300)</script></body></html>""")
                else -> Triple(404, "text/plain", "")
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", type)
            if (path == "/protected" && code == 503) exchange.responseHeaders.add("Server", "cloudflare")
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        ChromiumBrowser.shutdown()
    }

    private fun hasBrowser() = File(browser).isFile.also { if (!it) println("Pas de Chromium ($browser) : test ignoré") }

    @Test
    fun findsTheAddressThePageRequests() = runBlocking {
        if (!hasBrowser()) return@runBlocking
        var title: String? = null
        val resolver = WebViewResolver(Regex("""/api/source\?token="""), script = "document.title", scriptCallback = { title = it })
        val (found, _) = resolver.resolveUsingWebView("$base/player")
        val request = assertNotNull(found, "requête interceptée")
        assertEquals("c2VjcmV0LTQy", request.url.queryParameter("token"))
        assertEquals("\"Lecteur\"", title)
        // As an interceptor: the extension's request is replaced by the one the page made.
        val text = app.get("$base/player", interceptor = WebViewResolver(Regex("/api/source"))).text
        assertTrue("video.m3u8" in text, text)
    }

    @Test
    fun passesACloudflareCheck() = runBlocking {
        if (!hasBrowser()) return@runBlocking
        val killer = CloudflareKiller()
        val text = app.get("$base/protected", interceptor = killer).text
        assertEquals("contenu protégé", text)
        assertEquals("ok", killer.savedCookies["127.0.0.1"]?.get("cf_clearance"))
        assertTrue(killer.getCookieHeaders("$base/protected")["cookie"]!!.contains("cf_clearance=ok"))
    }
}
