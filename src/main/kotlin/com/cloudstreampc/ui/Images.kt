package com.cloudstreampc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/** Small image loader: downloads with the same HTTP client as extensions (so poster headers work). */
object ImageLoader {
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(300, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    })
    /** url -> time of the failure; retried after a minute. */
    private val failed = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val permits = Semaphore(8)

    fun cached(url: String): ImageBitmap? = cache[url]

    fun clear() {
        cache.clear()
        failed.clear()
    }

    suspend fun load(url: String, headers: Map<String, String>?): ImageBitmap? {
        cache[url]?.let { return it }
        failed[url]?.let { if (System.currentTimeMillis() - it < 60_000) return null }
        return withContext(Dispatchers.IO) {
            permits.withPermit {
                try {
                    val bytes = app.get(url, headers = headers ?: emptyMap()).body.bytes()
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { cache[url] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    failed[url] = System.currentTimeMillis()
                    null
                }
            }
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center,
    placeholder: @Composable () -> Unit = { Box(modifier.background(CsColors.SurfaceHigh)) },
) {
    var bitmap by remember(url) { mutableStateOf(url?.let { ImageLoader.cached(it) }) }
    if (url != null && bitmap == null) {
        LaunchedEffect(url) { bitmap = ImageLoader.load(url, headers) }
    }
    val image = bitmap
    if (image == null) {
        placeholder()
    } else {
        Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale, alignment = alignment)
    }
}
