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

    /** Streams sources and subtitles as the extractors find them, without duplicates. */
    suspend fun load(
        request: PlayRequest,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        request.api.loadLinks(request.playable.data, false, onSubtitle, onLink)
    }

    /**
     * Same rule as automatic playback: waits for every extractor,
     * or 10 s once at least one source was found (60 s at most).
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
        var waited = 0
        while (job.isActive && waited < 60_000 && (waited < 10_000 || links.isEmpty())) {
            delay(250)
            waited += 250
        }
        // Subtitles often arrive with the last links: give them a moment.
        if (!job.isActive) delay(300) else job.cancel()
        Resolved(links.toList(), subtitles.toList(), error)
    }
}
