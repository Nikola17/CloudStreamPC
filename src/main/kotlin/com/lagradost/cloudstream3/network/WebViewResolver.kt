package com.lagradost.cloudstream3.network

import com.cloudstreampc.core.Logger
import com.cloudstreampc.net.ChromiumBrowser
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Collections

/**
 * CloudStreamPC's version of CloudStream's WebViewResolver (the desktop library only has an empty
 * one). Same API, so extensions work unchanged: the page is opened in the computer's Chromium
 * browser in the background ([ChromiumBrowser]) and the requests it makes are reported.
 * This class is first on the class path, ahead of the library's.
 *
 * @param interceptUrl stops the page when a request matches.
 * @param additionalUrls other matching requests are collected too.
 * @param userAgent unused: the browser's own identity is what sites (Cloudflare) expect.
 * @param useOkhttp unused: the browser loads everything itself.
 * @param script JavaScript run once the page is loaded, its result given to [scriptCallback].
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = USER_AGENT,
    val useOkhttp: Boolean = true,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

    companion object {
        val DEFAULT_TIMEOUT = 60_000L
        var webViewUserAgent: String? = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixed = resolveUsingWebView(request).first
            chain.proceed(fixed ?: request)
        }
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = try {
        resolveUsingWebView(requestCreator(method, url, referer = referer, headers = headers), requestCallBack)
    } catch (e: IllegalArgumentException) {
        Logger.info("WebView : adresse invalide $url")
        null to emptyList()
    }

    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        val url = request.url.toString()
        if (!ChromiumBrowser.available) {
            Logger.info("WebView demandée par une extension, mais aucun navigateur Chromium n'est installé ($url)")
            return null to emptyList()
        }
        var fixed: Request? = null
        val extra = Collections.synchronizedList(mutableListOf<Request>())
        Logger.info("WebView : $url")
        runCatching {
            ChromiumBrowser.browse(
                url,
                headers = request.headers.toMap(),
                referer = request.header("referer"),
                script = script,
                onScript = scriptCallback,
                timeoutMs = timeout,
            ) { seen ->
                val asRequest = runCatching { seen.toRequest() }.getOrNull() ?: return@browse false
                when {
                    interceptUrl.containsMatchIn(seen.url) -> {
                        fixed = asRequest
                        runCatching { requestCallBack(asRequest) }
                        true
                    }
                    additionalUrls.any { it.containsMatchIn(seen.url) } -> {
                        extra += asRequest
                        runCatching { requestCallBack(asRequest) }.getOrDefault(false)
                    }
                    else -> false
                }
            }
        }.onFailure { Logger.error("WebView $url", it) }
        Logger.info("WebView terminée : ${fixed?.url ?: "rien d'intercepté"}, ${extra.size} autre(s)")
        return fixed to extra.toList()
    }

    private fun ChromiumBrowser.SeenRequest.toRequest(): Request {
        val body = body?.toRequestBody()
        return Request.Builder().url(url).headers(headers.toHeaders())
            .method(method, if (method == "GET" || method == "HEAD") null else body ?: ByteArray(0).toRequestBody())
            .build()
    }
}
