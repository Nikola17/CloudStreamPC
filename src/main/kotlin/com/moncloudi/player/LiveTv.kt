package com.moncloudi.player

import com.moncloudi.core.Logger
import com.moncloudi.core.Store
import com.moncloudi.providers.IptvProvider
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

/** Live TV around the player: backup sources, next / previous channel, the channel list. */
object LiveTv {
    /** Cached channel list for non-IPTV live extensions (e.g. FSTV WITV channels), keyed by api name. */
    private data class ExtensionChannel(val name: String, val url: String, val category: String)
    private val extensionChannelsCache = ConcurrentHashMap<String, Pair<Long, List<ExtensionChannel>>>()
    private const val EXT_CACHE_MS = 10 * 60 * 1000L

    /**
     * Live streams go through [LiveRelay] when it is enabled and mpv plays: IPTV channels, and the HLS
     * or TS streams of extensions (sports events…), which then also get the relay's reconnections and
     * the DNS chosen in the settings.
     */
    fun relayed(request: PlayRequest, link: ExtractorLink) =
        request.isLive && Store.settings.liveRelay && Store.settings.player == com.moncloudi.core.PlayerKind.MPV &&
            (request.api is IptvProvider || relayable(link))

    private fun relayable(link: ExtractorLink) =
        link.url.startsWith("http", ignoreCase = true) &&
            (link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 || link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO)

    private fun ExtractorLink.toSource() = LiveSource(url, getAllHeaders(), name)

    /**
     * Ways to play the channel, in the order the relay tries them: the chosen link, the channel's
     * other formats, then the same channel on the user's other IPTV accounts.
     */
    suspend fun sources(request: PlayRequest, chosen: ExtractorLink): List<LiveSource> {
        // Extensions: the other sources found for the stream are the backups.
        val provider = request.api as? IptvProvider ?: return (listOf(chosen) + LinkResolver.found(request.playable.data).filter { relayable(it) && it.name !in request.failedSources })
            .distinctBy { it.url }.map { it.toSource() }
            .also { list -> Logger.info("Direct : ${list.size} source(s) : ${list.joinToString { it.label }}") }
        val sameAccount = runCatching { provider.liveLinks(request) }.getOrDefault(emptyList())
        // The channel's copies on the same account (other quality or category), often on other servers.
        val copies = runCatching {
            provider.duplicates(request.response.url).mapNotNull { provider.liveLinks(it).firstOrNull() }
        }.getOrDefault(emptyList())
        val others = runCatching { otherAccounts(provider, request.response.url) }.getOrDefault(emptyList())
        return (listOf(chosen) + sameAccount + copies + others).distinctBy { it.url }.map { it.toSource() }
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

    /** Resolves channels for a non-IPTV live extension (e.g. FSTV): checks cached home page or fetches its live sections. */
    private suspend fun extensionChannels(api: MainAPI): List<ExtensionChannel> {
        val now = System.currentTimeMillis()
        extensionChannelsCache[api.name]?.takeIf { now - it.first < EXT_CACHE_MS }?.let { return it.second }
        // Prefer sections already loaded in HomeState when browsing this provider.
        val fromHome = if (com.moncloudi.ui.HomeState.loadedFor == api.name) {
            com.moncloudi.ui.HomeState.sections.toSortedMap().values.flatten()
                .flatMap { list -> list.list.map { ExtensionChannel(it.name, it.url, list.name) } }
                .distinctBy { it.url }
        } else emptyList()
        if (fromHome.size >= 2) {
            extensionChannelsCache[api.name] = now to fromHome
            return fromHome
        }
        // Otherwise fetch the provider's main "all channels" or live pages.
        val pages = api.mainPage.filter { it.data.contains("all", ignoreCase = true) || it.data.contains("live", ignoreCase = true) }
            .ifEmpty { api.mainPage.take(2) }
        val loaded = pages.flatMap { page ->
            runCatching {
                api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                    ?.items.orEmpty().flatMap { list -> list.list.map { ExtensionChannel(it.name, it.url, list.name) } }
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
        if (loaded.isNotEmpty()) extensionChannelsCache[api.name] = now to loaded
        return loaded
    }

    /** The channel [delta] places away in the same category (works for both IPTV and live extensions like FSTV). */
    suspend fun neighbour(current: PlayRequest, delta: Int): PlayRequest? {
        val provider = current.api as? IptvProvider
        if (provider != null) {
            val channels = provider.neighbours(current.response.url)
            if (channels.size < 2) return null
            val index = channels.indexOfFirst { it.url == current.response.url }.coerceAtLeast(0)
            val target = channels[Math.floorMod(index + delta, channels.size)]
            return requestFor(provider, target.url)
        }
        val channels = extensionChannels(current.api)
        if (channels.size < 2) return null
        val index = channels.indexOfFirst { it.url == current.response.url }.coerceAtLeast(0)
        val target = channels[Math.floorMod(index + delta, channels.size)]
        return requestFor(current.api, target.url)
    }

    suspend fun requestFor(provider: MainAPI, channelUrl: String): PlayRequest? {
        val response = runCatching { provider.load(channelUrl) }.getOrNull() ?: return null
        val queue = PlayQueue.build(response, emptyList())
        if (queue.isEmpty()) return null
        return PlayRequest(provider, response, queue, 0, autoPlay = true)
    }

    /**
     * The category's channels as a uosc menu (with what is on now for IPTV, or extension channels for FSTV);
     * choosing one sends `zap-to <url>` back to the app.
     */
    suspend fun channelMenu(current: PlayRequest): String? {
        val provider = current.api as? IptvProvider
        if (provider != null) {
            val channels = provider.neighbours(current.response.url)
            if (channels.isEmpty()) return null
            val now = System.currentTimeMillis()
            val items = channels.map { channel ->
                val on = channel.epgId?.let { provider.guide.current(it, now) }?.title
                mapOf(
                    "title" to channel.name,
                    "hint" to (on ?: ""),
                    "value" to listOf("script-message-to", "moncloudi", "zap-to", channel.url),
                )
            }
            val selected = channels.indexOfFirst { it.url == current.response.url }.coerceAtLeast(0)
            return com.moncloudi.core.compactJson(
                mapOf(
                    "type" to "moncloudi-channels",
                    "title" to channels.first().category,
                    "items" to items,
                    "selected_index" to selected + 1,
                    "search_style" to "on_demand",
                ),
            )
        }
        val channels = extensionChannels(current.api)
        if (channels.isEmpty()) return null
        val items = channels.map { channel ->
            mapOf(
                "title" to channel.name,
                "hint" to channel.category,
                "value" to listOf("script-message-to", "moncloudi", "zap-to", channel.url),
            )
        }
        val selected = channels.indexOfFirst { it.url == current.response.url }.coerceAtLeast(0)
        return com.moncloudi.core.compactJson(
            mapOf(
                "type" to "moncloudi-channels",
                "title" to current.api.name,
                "items" to items,
                "selected_index" to selected + 1,
                "search_style" to "on_demand",
            ),
        )
    }
}
