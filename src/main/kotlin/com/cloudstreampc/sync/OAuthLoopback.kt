package com.cloudstreampc.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Receives the answer of a login made in the browser on http://localhost:47920/callback,
 * the address the user gives as "redirect URL" when creating their API client.
 */
object OAuthLoopback {
    const val PORT = 47920
    const val REDIRECT_URL = "http://localhost:$PORT/callback"

    private fun page(title: String, body: String) = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
        <title>CloudStreamPC</title><style>body{background:#0b0b0d;color:#eee;font-family:system-ui,sans-serif;
        display:flex;align-items:center;justify-content:center;height:100vh;margin:0}div{text-align:center}
        h1{font-size:22px}p{color:#aaa}</style></head><body><div><h1>$title</h1><p>$body</p></div></body></html>"""

    private val done = page("Connexion réussie", "Vous pouvez fermer cette page et revenir à CloudStreamPC.")

    /** Script for implicit grants: the token is in the URL fragment, which browsers never send to servers. */
    private val fragmentPage = """<!doctype html><html><head><meta charset="utf-8"></head><body style="background:#0b0b0d">
        <script>location.replace('/token?' + location.hash.substring(1));</script></body></html>"""

    /**
     * Opens [authorizeUrl] in the browser and waits (5 min at most) for the redirect.
     * @return the query parameters of the redirect (code, state, access_token…)
     */
    suspend fun authorize(authorizeUrl: String, openBrowser: (String) -> Unit): Map<String, String> {
        val result = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
        fun params(query: String?) = query.orEmpty().split("&").filter { "=" in it }.associate {
            URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
        }
        fun reply(exchange: com.sun.net.httpserver.HttpExchange, html: String) {
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/callback") { exchange ->
            val query = params(exchange.requestURI.rawQuery)
            if (query.isEmpty()) {
                reply(exchange, fragmentPage)
            } else {
                reply(exchange, if ("error" in query) page("Connexion refusée", query["error_description"] ?: query["error"]!!) else done)
                result.complete(query)
            }
        }
        server.createContext("/token") { exchange ->
            val query = params(exchange.requestURI.rawQuery)
            reply(exchange, if ("access_token" in query) done else page("Connexion refusée", query["error"] ?: "Pas de jeton reçu."))
            result.complete(query)
        }
        server.start()
        return try {
            openBrowser(authorizeUrl)
            withTimeout(5 * 60_000) { result.await() }
        } finally {
            server.stop(0)
        }
    }
}
