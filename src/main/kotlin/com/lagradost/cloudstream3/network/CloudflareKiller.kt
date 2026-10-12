package com.lagradost.cloudstream3.network

import com.cloudstreampc.core.Logger
import com.cloudstreampc.net.ChromiumBrowser
import com.cloudstreampc.net.browseUntil
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * CloudStream's Cloudflare helper (part of the Android app, so missing on desktop), same API for the
 * extensions that use it. When a site answers with Cloudflare's check, the page is opened in the
 * computer's Chromium browser in the background; if Cloudflare wants a person, a small window asks
 * the user once. The `cf_clearance` cookies are then reused with the browser's identity.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> = cookie.split(";").associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }

        /** Cookies solved once are shared by every extension, for the session. */
        private val shared = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    }

    val savedCookies: MutableMap<String, Map<String, String>> = shared

    /** Headers with the cookies and the browser's User-Agent. */
    fun getCookieHeaders(url: String): Headers {
        val userAgent = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) } ?: emptyMap()
        val cookies = savedCookies[URI(url).host] ?: emptyMap()
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return (userAgent + if (cookieHeader.isNotEmpty()) mapOf("cookie" to cookieHeader) else emptyMap()).toHeaders()
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        savedCookies[request.url.host]?.let { return@runBlocking proceed(request, it) }
        val response = chain.proceed(request)
        if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) return@runBlocking response
        response.close()
        bypass(request)?.let { return@runBlocking it }
        Logger.info("Cloudflare non passé : ${request.url}")
        chain.proceed(request)
    }

    private fun proceed(request: Request, cookies: Map<String, String>): Response {
        val builder = request.newBuilder()
        WebViewResolver.webViewUserAgent?.let { builder.header("user-agent", it) }
        val existing = request.header("cookie")?.let(::parseCookieMap).orEmpty()
        builder.header("cookie", (existing + cookies).entries.joinToString("; ") { "${it.key}=${it.value}" })
        return app.baseClient.newCall(builder.build()).execute()
    }

    private suspend fun bypass(request: Request): Response? {
        if (!ChromiumBrowser.available) {
            Logger.info("Cloudflare : aucun navigateur Chromium installé pour passer la vérification")
            return null
        }
        val url = request.url.toString()
        val host = request.url.host
        fun solved(cookies: Map<String, String>) = cookies.containsKey("cf_clearance")
        Logger.info("Cloudflare : vérification de $host dans le navigateur")
        var cookies = runCatching { ChromiumBrowser.cookies(url) }.getOrDefault(emptyMap())
        if (!solved(cookies)) {
            ChromiumBrowser.browseUntil(url, 25_000) {
                cookies = ChromiumBrowser.cookies(url)
                solved(cookies)
            }
        }
        // Cloudflare wants a person: ask once, in a small window.
        if (!solved(cookies)) cookies = ChromiumBrowser.askUser(url, ::solved) ?: return null
        if (!solved(cookies)) return null
        savedCookies[host] = cookies
        Logger.info("Cloudflare passé pour $host")
        return proceed(request, cookies)
    }
}
