package com.cloudstreampc.cast

import com.cloudstreampc.core.Logger
import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * TVs can't send the headers (Referer, User-Agent…) the video hosts require, nor reach the torrent
 * engine on this computer: they read the video from this proxy, which fetches it with the right
 * headers. HLS playlists are rewritten so their segments go through the proxy too.
 */
object CastProxy {
    private class Target(val url: String, val headers: Map<String, String>)

    private val targets = ConcurrentHashMap<String, Target>()
    private var server: HttpServer? = null

    /** Address of this computer on the network that reaches [deviceHost]. */
    fun localAddressFor(deviceHost: String): String = DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName(deviceHost), 9)
        socket.localAddress.hostAddress
    }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { return it.address.port }
        val created = HttpServer.create(InetSocketAddress(0), 0)
        created.executor = Executors.newCachedThreadPool { Thread(it, "cast-proxy").apply { isDaemon = true } }
        created.createContext("/v/") { exchange -> runCatching { serve(exchange) }.onFailure { Logger.info("Proxy de diffusion : ${it.message}") } }
        created.start()
        server = created
        return created.address.port
    }

    /** URL the device at [deviceHost] can open to play [url]. */
    fun publish(url: String, headers: Map<String, String>, deviceHost: String): String {
        val port = ensureStarted()
        val id = UUID.randomUUID().toString().replace("-", "")
        targets[id] = Target(url, headers)
        val name = if (isPlaylist(url)) "index.m3u8" else "video"
        return "http://${localAddressFor(deviceHost)}:$port/v/$id/$name"
    }

    private fun isPlaylist(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    private fun serve(exchange: HttpExchange) {
        val parts = exchange.requestURI.path.removePrefix("/v/").split("/", limit = 2)
        val target = targets[parts[0]]
        if (target == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        // Segments of a playlist: /v/<id>/s?u=<absolute url>
        val upstream = if (parts.getOrNull(1) == "s") {
            exchange.requestURI.rawQuery?.substringAfter("u=")?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: target.url
        } else {
            target.url
        }
        val request = Request.Builder().url(upstream).apply {
            target.headers.forEach { (k, v) -> header(k, v) }
            exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            if (exchange.requestMethod == "HEAD") head()
        }.build()
        app.baseClient.newCall(request).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty()
            val playlist = isPlaylist(upstream) || "mpegurl" in contentType.lowercase()
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (playlist && exchange.requestMethod != "HEAD") {
                val text = rewrite(response.body.string(), upstream, parts[0])
                val bytes = text.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return
            }
            listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length").forEach { name ->
                response.header(name)?.let { exchange.responseHeaders.add(name, it) }
            }
            val length = response.header("Content-Length")?.toLongOrNull() ?: 0
            exchange.sendResponseHeaders(response.code, if (exchange.requestMethod == "HEAD") -1 else length)
            if (exchange.requestMethod != "HEAD") {
                exchange.responseBody.use { out ->
                    runCatching { response.body.byteStream().copyTo(out, 256 * 1024) } // the TV stops reading when it seeks
                }
            }
        }
    }

    /** Points every URI of an HLS playlist (variants, segments, keys) to the proxy. */
    internal fun rewrite(playlist: String, base: String, id: String): String {
        fun proxied(uri: String): String {
            val absolute = URI(base).resolve(uri.trim()).toString()
            return "/v/$id/s?u=" + java.net.URLEncoder.encode(absolute, Charsets.UTF_8)
        }
        return playlist.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${proxied(it.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }

    fun clear() = targets.clear()
}
