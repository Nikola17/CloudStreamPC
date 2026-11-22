package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/** Fetches the sources (and subtitles) of a movie or episode from its extension. */
object LinkResolver {
    class Resolved(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>, val error: Throwable?)

    /** Sources found for the last item asked (live TV: the others are the relay's backups). */
    private val found = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<ExtractorLink>>()

    fun found(data: String): List<ExtractorLink> = found[data].orEmpty()

    /** Streams sources and subtitles as the extractors find them, without duplicates. */
    suspend fun load(
        request: PlayRequest,
        onSubtitle: (SubtitleFile) -> Unit,
        onFound: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val data = request.playable.data
        val bucket = CopyOnWriteArrayList<ExtractorLink>()
        found.keys.removeIf { it != data }
        found[data] = bucket
        val onLink: (ExtractorLink) -> Unit = { link -> bucket += link; onFound(link) }
        if (data.startsWith(PlayQueue.TORRENT_DATA)) {
            val torrent = data.removePrefix(PlayQueue.TORRENT_DATA)
            val type = if (torrent.startsWith("magnet:")) com.lagradost.cloudstream3.utils.ExtractorLinkType.MAGNET else com.lagradost.cloudstream3.utils.ExtractorLinkType.TORRENT
            onLink(com.lagradost.cloudstream3.utils.newExtractorLink(request.api.name, "Torrent", torrent, type))
            return@withContext true
        }
        request.api.loadLinks(data, false, onSubtitle, onLink)
    }

    /**
     * Same smart fast rule as automatic playback: finishes as soon as all extractors finish,
     * or 1.5–3.5 s once playable sources have arrived (45 s max if none arrived yet).
     */
    suspend fun resolve(request: PlayRequest): Resolved = coroutineScope {
        val links = CopyOnWriteArrayList<ExtractorLink>()
        val subtitles = CopyOnWriteArrayList<SubtitleFile>()
        var error: Throwable? = null
        val job = async(Dispatchers.IO) {
            try {
                load(
                    request,
                    { sub -> if (subtitles.none { it.url == sub.url }) subtitles += sub },
                    { link -> if (links.none { it.url == link.url }) links += link },
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.error("loadLinks ${request.api.name}", t)
                error = t
            }
        }
        val startedAt = System.currentTimeMillis()
        var firstFoundAt = 0L
        var lastFoundAt = 0L
        var lastCount = 0
        while (job.isActive) {
            val now = System.currentTimeMillis()
            if (now - startedAt >= 45_000) break
            val count = links.size
            if (count != lastCount) {
                if (firstFoundAt == 0L && count > 0) firstFoundAt = now
                lastFoundAt = now
                lastCount = count
            }
            if (count > 0) {
                val sinceFirst = now - firstFoundAt
                val sinceLast = now - lastFoundAt
                if (sinceFirst >= 3_500 || (sinceFirst >= 1_500 && sinceLast >= 1_200)) break
            }
            delay(100)
        }
        if (!job.isActive) delay(150) else job.cancel()
        Resolved(links.toList(), subtitles.toList(), error)
    }
}
