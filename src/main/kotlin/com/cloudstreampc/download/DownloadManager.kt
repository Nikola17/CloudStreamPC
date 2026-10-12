package com.cloudstreampc.download

import androidx.compose.runtime.mutableStateListOf
import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.readJsonFile
import com.cloudstreampc.core.writeJsonFile
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.player.PlaybackManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val episodeLabel: String? = null,
    val posterUrl: String? = null,
    val apiName: String = "",
    val pageUrl: String = "",
    val episodeData: String = "",
    val linkUrl: String = "",
    val linkName: String = "",
    val hls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val filePath: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    /** HLS: segments done / total. */
    val doneSegments: Int = 0,
    val totalSegments: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Subtitle files saved next to the video (mpv loads them by their name). */
    val subtitleFiles: List<String> = emptyList(),
    /** Why a queued download waits (off-peak hours). */
    val note: String? = null,
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.DONE -> 1f
            hls && totalSegments > 0 -> doneSegments.toFloat() / totalSegments
            totalBytes > 0 -> downloadedBytes.toFloat() / totalBytes
            else -> 0f
        }
    val file: File get() = File(filePath)
}

/** Downloads movies and episodes (direct files and HLS streams) to the download folder. */
object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFile get() = File(AppDirs.root, "downloads.json")
    private val jobs = mutableMapOf<String, Job>()
    private var permits = Semaphore(Store.settings.parallelDownloads.coerceIn(1, 5))

    val items = mutableStateListOf<DownloadItem>().apply {
        addAll(readJsonFile(stateFile) { emptyList<DownloadItem>() }.map {
            // Interrupted by closing the app: resume later from where it stopped.
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.PAUSED) else it
        })
    }

    val folder: File
        get() = (Store.settings.downloadDir?.let(::File)
            ?: defaultFolder()).apply { mkdirs() }

    /** Videos\CloudStreamPC (Windows, Linux) or Movies/CloudStreamPC (macOS). */
    private fun defaultFolder(): File {
        val home = System.getProperty("user.home")
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        return File(home, if (isMac) "Movies/CloudStreamPC" else "Videos/CloudStreamPC")
    }

    fun applyParallelSetting() {
        permits = Semaphore(Store.settings.parallelDownloads.coerceIn(1, 5))
    }

    @Synchronized
    private fun update(id: String, block: (DownloadItem) -> DownloadItem) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = block(items[index])
        save()
    }

    private var lastSave = 0L
    private fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 1500) return
        lastSave = now
        runCatching { writeJsonFile(stateFile, items.toList()) }
    }

    fun find(apiName: String, episodeData: String): DownloadItem? =
        items.firstOrNull { it.apiName == apiName && it.episodeData == episodeData }

    /** Finds the best source of [request] and queues it. Returns an error message, or null. */
    suspend fun download(request: PlayRequest): String? {
        find(request.api.name, request.playable.data)?.let {
            if (it.status != DownloadStatus.FAILED) return "Déjà dans les téléchargements"
            delete(it.id)
        }
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<com.lagradost.cloudstream3.SubtitleFile>()
        withContext(Dispatchers.IO) {
            runCatching { request.api.loadLinks(request.playable.data, false, { subtitles += it }) { links += it } }
        }
        val link = PlaybackManager.bestLink(links.filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 })
            ?: return "Aucune source téléchargeable"
        enqueue(request, link, subtitles)
        return null
    }

    /**
     * Every episode of [requests] not downloaded yet, one after the other (each one looks for its
     * best source). Returns how many were added.
     */
    suspend fun downloadAll(requests: List<PlayRequest>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val todo = requests.filter { r -> find(r.api.name, r.playable.data)?.let { it.status == DownloadStatus.FAILED } ?: true }
        var added = 0
        todo.forEachIndexed { i, request ->
            if (download(request) == null) added++
            onProgress(i + 1, todo.size)
        }
        return added
    }

    /** Subtitles in the preferred languages (Paramètres → Lecteur), two at most. */
    internal fun preferredSubtitles(all: List<com.lagradost.cloudstream3.SubtitleFile>): List<com.lagradost.cloudstream3.SubtitleFile> {
        val wanted = Store.settings.subtitleLanguages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        return all.filter { sub ->
            val lang = sub.lang.lowercase()
            val words = lang.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            wanted.any { code -> lang == code || words.any { w -> w == code || (code.length >= 2 && w.startsWith(code)) } }
        }.distinctBy { it.url }.take(2)
    }

    fun enqueue(request: PlayRequest, link: ExtractorLink, subtitles: List<com.lagradost.cloudstream3.SubtitleFile> = emptyList()) {
        val playable = request.playable
        val hls = link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8")
        val extension = if (hls) "ts" else link.url.substringBefore("?").substringAfterLast('.', "mp4").takeIf { it.length in 2..4 } ?: "mp4"
        val baseName = listOfNotNull(playable.title, playable.label?.substringBefore("  "))
            .joinToString(" - ").replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
        var file = File(folder, "$baseName.$extension")
        var n = 2
        while (file.exists() || items.any { it.filePath == file.path }) file = File(folder, "$baseName ($n).${extension}").also { n++ }
        val item = DownloadItem(
            title = playable.title,
            episodeLabel = playable.label,
            posterUrl = request.response.posterUrl,
            apiName = request.api.name,
            pageUrl = request.response.url,
            episodeData = playable.data,
            linkUrl = link.url,
            linkName = link.name,
            hls = hls,
            headers = link.getAllHeaders(),
            filePath = file.path,
        )
        items.add(0, item)
        save(true)
        start(item.id)
        if (Store.settings.downloadSubtitles) {
            val chosen = preferredSubtitles(subtitles)
            if (chosen.isNotEmpty()) scope.launch { saveSubtitles(item, chosen) }
        }
    }

    /** `Video.fr.srt` next to `Video.mp4`: mpv shows them with the video. */
    private fun saveSubtitles(item: DownloadItem, subtitles: List<com.lagradost.cloudstream3.SubtitleFile>) {
        val base = item.file.nameWithoutExtension
        val code = Store.settings.subtitleLanguages.split(',').firstOrNull()?.trim()?.ifBlank { null } ?: "sub"
        val saved = subtitles.mapIndexedNotNull { i, sub ->
            runCatching {
                val data = fetchBytes(sub.url, sub.headers ?: emptyMap())
                val ext = sub.url.substringBefore('?').substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                val name = if (i == 0) "$base.$code.$ext" else "$base.$code${i + 1}.$ext"
                File(item.file.parentFile, name).apply { writeBytes(data) }.path
            }.onFailure { Logger.error("Sous-titres ${sub.lang}", it) }.getOrNull()
        }
        if (saved.isNotEmpty()) update(item.id) { it.copy(subtitleFiles = saved) }
    }

    /** Off-peak hours: whether downloads may run at [hour] (the window can cross midnight). */
    internal fun allowedAt(hour: Int, start: Int, end: Int): Boolean =
        if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end

    private fun allowedNow(): Boolean {
        val s = Store.settings
        return !s.offPeakOnly || allowedAt(java.time.LocalTime.now().hour, s.offPeakStart, s.offPeakEnd)
    }

    /** Waits for the off-peak hours, showing why on the download. */
    private suspend fun waitForWindow(id: String) {
        if (allowedNow()) return
        val s = Store.settings
        update(id) { it.copy(status = DownloadStatus.QUEUED, note = "Heures creuses : reprise à ${s.offPeakStart} h") }
        while (!allowedNow()) kotlinx.coroutines.delay(30_000)
        update(id) { it.copy(status = DownloadStatus.RUNNING, note = null) }
    }

    fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        jobs[id] = scope.launch {
            waitForWindow(id)
            permits.withPermit {
                update(id) { it.copy(status = DownloadStatus.RUNNING, note = null) }
                val item = items.firstOrNull { it.id == id } ?: return@withPermit
                try {
                    if (item.hls) downloadHls(item) else downloadFile(item)
                    update(id) { it.copy(status = DownloadStatus.DONE) }
                    Logger.info("Téléchargement terminé : ${item.file.name}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.error("Téléchargement ${item.file.name}", t)
                    update(id) { it.copy(status = DownloadStatus.FAILED, error = t.message ?: t.toString()) }
                }
            }
            save(true)
        }
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { it.copy(status = DownloadStatus.PAUSED) }
        save(true)
    }

    fun delete(id: String, deleteFile: Boolean = true) {
        jobs.remove(id)?.cancel()
        val item = items.firstOrNull { it.id == id } ?: return
        if (deleteFile) {
            item.file.delete()
            item.subtitleFiles.forEach { File(it).delete() }
        }
        items.remove(item)
        save(true)
    }

    private fun request(url: String, headers: Map<String, String>, range: Long = 0): Request =
        Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (range > 0) header("Range", "bytes=$range-")
        }.build()

    private suspend fun downloadFile(item: DownloadItem) {
        val file = item.file
        val existing = if (file.exists()) file.length() else 0L
        app.baseClient.newCall(request(item.linkUrl, item.headers, existing)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val resumed = response.code == 206
            val body = response.body
            val total = if (resumed) existing + body.contentLength() else body.contentLength()
            var done = if (resumed) existing else 0L
            update(item.id) { it.copy(totalBytes = total, downloadedBytes = done) }
            RandomAccessFile(file, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(done)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        waitForWindow(item.id)
                        val read = input.read(buffer)
                        if (read < 0) break
                        Throttle.take(read)
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            update(item.id) { it.copy(downloadedBytes = done) }
                        }
                    }
                }
            }
            update(item.id) { it.copy(downloadedBytes = done, totalBytes = maxOf(total, done)) }
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String =
        app.baseClient.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body.string()
        }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        var lastError: Throwable? = null
        repeat(3) {
            try {
                return app.baseClient.newCall(request(url, headers)).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body.bytes()
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Échec du téléchargement")
    }

    private suspend fun downloadHls(item: DownloadItem) {
        var url = item.linkUrl
        var text = fetchText(url, item.headers)
        if (Hls.isMaster(text)) {
            val limit = Store.settings.preferredQuality
            val variants = Hls.variants(text, url)
            val chosen = variants.filter { limit == null || (it.height ?: 0) <= limit }.maxByOrNull { it.bandwidth }
                ?: variants.minByOrNull { it.bandwidth } ?: error("Playlist HLS vide")
            url = chosen.url
            text = fetchText(url, item.headers)
        }
        val media = Hls.media(text, url)
        if (media.segments.isEmpty()) error("Aucun segment vidéo")
        update(item.id) { it.copy(totalSegments = media.segments.size) }

        val keys = mutableMapOf<String, ByteArray>()
        val file = item.file
        RandomAccessFile(file, "rw").use { out ->
            var start = item.doneSegments
            var written = item.downloadedBytes
            if (start == 0 || written > out.length()) {
                start = 0
                written = 0
                out.setLength(0)
                media.initUrl?.let { written += fetchBytes(it, item.headers).also(out::write).size }
            } else {
                out.setLength(written)
            }
            out.seek(written)
            for (index in start until media.segments.size) {
                coroutineContext.ensureActive()
                waitForWindow(item.id)
                val segment = media.segments[index]
                var data = fetchBytes(segment.url, item.headers)
                Throttle.take(data.size)
                val key = segment.key
                if (key != null && key.method == "AES-128" && key.uri != null) {
                    val keyBytes = keys.getOrPut(key.uri) { fetchBytes(key.uri, item.headers) }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(Hls.ivFor(segment)))
                    data = cipher.doFinal(data)
                } else if (key != null && key.method != "AES-128") {
                    error("Chiffrement ${key.method} non pris en charge")
                }
                out.write(data)
                written += data.size
                update(item.id) { it.copy(doneSegments = index + 1, downloadedBytes = written) }
            }
        }
    }
}

/** Speed limit shared by all downloads (Paramètres → Téléchargements), at most one second of burst. */
internal object Throttle {
    private var available = 0.0
    private var last = System.nanoTime()

    suspend fun take(bytes: Int) {
        val limit = Store.settings.downloadSpeedLimit * 1024.0
        if (limit <= 0) return
        val waitMs = synchronized(this) {
            val now = System.nanoTime()
            available = minOf(limit, available + (now - last) / 1e9 * limit)
            last = now
            available -= bytes
            if (available < 0) (-available / limit * 1000).toLong() else 0L
        }
        if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
    }
}
