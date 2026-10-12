package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.providers.IptvProvider
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.ExtractorLink

/** Live TV around the player: backup sources, next / previous channel, the channel list. */
object LiveTv {
    /** Live channels of IPTV accounts go through [LiveRelay] (when enabled and played in mpv). */
    fun relayed(request: PlayRequest) =
        request.isLive && Store.settings.liveRelay && Store.settings.player == com.cloudstreampc.core.PlayerKind.MPV &&
            request.api is IptvProvider

    private fun ExtractorLink.toSource() = LiveSource(url, getAllHeaders(), name)

    /**
     * Ways to play the channel, in the order the relay tries them: the chosen link, the channel's
     * other formats, then the same channel on the user's other IPTV accounts.
     */
    suspend fun sources(request: PlayRequest, chosen: ExtractorLink): List<LiveSource> {
        val provider = request.api as? IptvProvider ?: return listOf(chosen.toSource())
        val sameAccount = runCatching { provider.liveLinks(request) }.getOrDefault(emptyList())
        val others = runCatching { otherAccounts(provider, request.response.url) }.getOrDefault(emptyList())
        return (listOf(chosen) + sameAccount + others).distinctBy { it.url }.map { it.toSource() }
            .also { list -> Logger.info("Direct : ${list.size} source(s) : ${list.joinToString { it.label }}") }
    }

    private suspend fun IptvProvider.liveLinks(request: PlayRequest): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        loadLinks(request.playable.data, false, {}) { links += it }
        return links
    }

    private suspend fun otherAccounts(provider: IptvProvider, channelUrl: String): List<ExtractorLink> {
        val (epgId, name) = provider.channelIdentity(channelUrl) ?: return emptyList()
        return iptvProviders().filter { it !== provider }.flatMap { other ->
            val item = runCatching { other.sameChannel(epgId, name) }.getOrNull() ?: return@flatMap emptyList()
            other.liveLinks(item)
        }
    }

    fun iptvProviders(): List<IptvProvider> = synchronized(APIHolder.allProviders) { APIHolder.allProviders.filterIsInstance<IptvProvider>() }

    /** The channel [delta] places away in the same category, ready to play. */
    suspend fun neighbour(current: PlayRequest, delta: Int): PlayRequest? {
        val provider = current.api as? IptvProvider ?: return null
        val channels = provider.neighbours(current.response.url)
        if (channels.size < 2) return null
        val index = channels.indexOfFirst { it.url == current.response.url }.coerceAtLeast(0)
        val target = channels[Math.floorMod(index + delta, channels.size)]
        return requestFor(provider, target.url)
    }

    suspend fun requestFor(provider: IptvProvider, channelUrl: String): PlayRequest? {
        val response = provider.load(channelUrl) ?: return null
        val queue = PlayQueue.build(response, emptyList())
        if (queue.isEmpty()) return null
        return PlayRequest(provider, response, queue, 0, autoPlay = true)
    }

    /**
     * The category's channels as a uosc menu (with what is on now); choosing one sends
     * `zap-to <url>` back to the app.
     */
    suspend fun channelMenu(current: PlayRequest): String? {
        val provider = current.api as? IptvProvider ?: return null
        val channels = provider.neighbours(current.response.url)
        if (channels.isEmpty()) return null
        val now = System.currentTimeMillis()
        val items = channels.map { channel ->
            val on = channel.epgId?.let { provider.guide.current(it, now) }?.title
            mapOf(
                "title" to channel.name,
                "hint" to (on ?: ""),
                "value" to listOf("script-message-to", "cloudstreampc", "zap-to", channel.url),
            )
        }
        val selected = channels.indexOfFirst { it.url == current.response.url }.coerceAtLeast(0)
        return com.cloudstreampc.core.compactJson(
            mapOf(
                "type" to "cspc-channels",
                "title" to channels.first().category,
                "items" to items,
                "selected_index" to selected + 1,
                "search_style" to "on_demand",
            ),
        )
    }
}
