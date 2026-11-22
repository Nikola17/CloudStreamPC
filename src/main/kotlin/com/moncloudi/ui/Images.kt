package com.moncloudi.ui

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
import com.moncloudi.core.AppDirs
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast image loader with in-memory LRU, persistent disk cache, and in-flight request deduplication
 * (downloads with the same HTTP client as extensions so poster headers & DoH apply).
 */
object ImageLoader {
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(350, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 350
    })
    /** Deduplicates simultaneous requests for the exact same poster URL. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    /** url -> time of the failure; retried after a minute. */
    private val failed = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val permits = Semaphore(10)
    private val diskDir: File get() = File(AppDirs.cache, "images").apply { mkdirs() }
    private const val MAX_DISK_FILES = 1_200

    fun cached(url: String): ImageBitmap? = cache[url]

    fun clear() {
        cache.clear()
        failed.clear()
        runCatching { diskDir.listFiles()?.forEach { it.delete() } }
    }

    private fun diskFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(diskDir, "$hash.img")
    }

    private fun trimDiskCacheIfNeeded() {
        val files = diskDir.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_FILES + 200).forEach { runCatching { it.delete() } }
    }

    suspend fun load(url: String, headers: Map<String, String>?): ImageBitmap? {
        cache[url]?.let { return it }
        failed[url]?.let { if (System.currentTimeMillis() - it < 60_000) return null }

        val mine = CompletableDeferred<ImageBitmap?>()
        val existing = inFlight.putIfAbsent(url, mine)
        if (existing != null) return existing.await()

        return try {
            val loaded = withContext(Dispatchers.IO) {
                val file = diskFile(url)
                if (file.isFile && file.length() > 0) {
                    val fromDisk = runCatching {
                        file.setLastModified(System.currentTimeMillis())
                        org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
                    }.getOrNull()
                    if (fromDisk != null) {
                        cache[url] = fromDisk
                        return@withContext fromDisk
                    } else {
                        runCatching { file.delete() }
                    }
                }
                permits.withPermit {
                    try {
                        val bytes = app.get(url, headers = headers ?: emptyMap()).body.bytes()
                        val bmp = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                        cache[url] = bmp
                        runCatching {
                            file.writeBytes(bytes)
                            if (Math.random() < 0.02) trimDiskCacheIfNeeded()
                        }
                        bmp
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        failed[url] = System.currentTimeMillis()
                        null
                    }
                }
            }
            mine.complete(loaded)
            loaded
        } catch (e: CancellationException) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(url, mine)
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
