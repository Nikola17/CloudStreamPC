package com.cloudstreampc.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.LinkResolver
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.player.PlaybackManager
import com.cloudstreampc.player.TorrentEngine
import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** A device that can play videos sent from this computer. */
sealed interface CastTarget {
    val name: String
    val host: String
    val kind: String

    data class Dlna(val device: DlnaDevice) : CastTarget {
        override val name get() = device.name
        override val host get() = device.host
        override val kind get() = "DLNA"
    }

    data class Chromecast(val device: ChromecastDevice) : CastTarget {
        override val name get() = device.name
        override val host get() = device.host
        override val kind get() = "Chromecast"
    }
}

data class CastingNow(val target: CastTarget, val title: String, val paused: Boolean = false)

/** Finds TVs on the network and sends them the best source of a movie or episode. */
object CastManager {
    var devices by mutableStateOf<List<CastTarget>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var current by mutableStateOf<CastingNow?>(null)
        private set
    private var chromecast: ChromecastSession? = null

    suspend fun discover() {
        searching = true
        try {
            devices = withContext(Dispatchers.IO) {
                coroutineScope {
                    val cast = async { ChromecastSession.discover().map { CastTarget.Chromecast(it) } }
                    val dlna = async { runCatching { Dlna.discover().map { CastTarget.Dlna(it) } }.getOrDefault(emptyList()) }
                    // A TV can be both: keep the Chromecast entry.
                    (cast.await() + dlna.await()).distinctBy { it.host to it.kind }
                }
            }
        } finally {
            searching = false
        }
    }

    internal fun contentType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    /** Finds the best source of [request] and plays it on [target]. */
    suspend fun cast(target: CastTarget, request: PlayRequest, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        onStatus("Recherche de la meilleure source…")
        val resolved = LinkResolver.resolve(request)
        val best = PlaybackManager.bestLink(resolved.links, remembered = PlaybackManager.rememberedSource(request))
            ?: error("Aucune source lisible.")
        val (url, headers) = if (TorrentEngine.isTorrent(best)) {
            onStatus("Torrent : connexion aux sources…")
            TorrentEngine.prepare(best, request.playable) to emptyMap()
        } else {
            best.url to best.getAllHeaders()
        }
        stop() // the previous video, if any
        val sent = CastProxy.publish(url, mapOf("User-Agent" to USER_AGENT) + headers, target.host)
        val title = listOfNotNull(request.playable.title, request.playable.label?.substringBefore("  ")).joinToString(" · ")
        val type = contentType(url)
        onStatus("Envoi à ${target.name}…")
        when (target) {
            is CastTarget.Chromecast -> {
                val session = ChromecastSession(target.device)
                chromecast = session
                session.load(sent, type, title, request.response.posterUrl, PlaybackManager.resumePosition(request) ?: 0.0)
            }
            is CastTarget.Dlna -> Dlna.play(target.device, sent, title, type)
        }
        Store.addHistory(request.savedItem, request.playable.label, request.playable.data)
        current = CastingNow(target, title)
        Logger.info("Diffusion sur ${target.name} (${target.kind}) : ${best.name}")
    }

    suspend fun togglePause() = withContext(Dispatchers.IO) {
        val now = current ?: return@withContext
        runCatching {
            when (val target = now.target) {
                is CastTarget.Chromecast -> if (now.paused) chromecast?.play() else chromecast?.pause()
                is CastTarget.Dlna -> if (now.paused) Dlna.resume(target.device) else Dlna.pause(target.device)
            }
            current = now.copy(paused = !now.paused)
        }.onFailure { Logger.error("Diffusion", it) }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val now = current
        runCatching {
            chromecast?.stop()
            chromecast?.close()
            (now?.target as? CastTarget.Dlna)?.let { Dlna.stop(it.device) }
        }
        chromecast = null
        current = null
        CastProxy.clear()
    }
}
