package com.cloudstreampc.providers

import com.cloudstreampc.core.IptvAccount
import com.cloudstreampc.core.IptvType
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

private const val PAGE_SIZE = 60

/** One entry of an IPTV catalog (channel, movie or series). */
internal data class IptvItem(
    val kind: Kind,
    val id: String,
    val name: String,
    val category: String,
    val poster: String?,
    val rating: Double? = null,
    val added: Long = 0,
    /** Direct stream URL (M3U) or container extension (Xtream movies). */
    val extra: String? = null,
    /** Channel id in the XMLTV guide. */
    val epgId: String? = null,
    /** Days of replay the provider keeps for this channel (Xtream `tv_archive`), 0 when none. */
    val archiveDays: Int = 0,
) {
    enum class Kind { LIVE, MOVIE, SERIES }
}

/** What the Xtream server says about the subscription (`user_info` / `server_info`). */
data class IptvAccountInfo(
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
    /** Epoch ms; null when the subscription has no end. */
    val expiresAt: Long? = null,
    /** Stream formats allowed by the plan, in the server's order ("ts", "m3u8"…). */
    val formats: List<String> = emptyList(),
    val status: String? = null,
    val timezone: String? = null,
)

/**
 * An IPTV account shown as a CloudStream provider: live TV, movies and series,
 * one home row per category of the panel.
 */
class IptvProvider(val account: IptvAccount) : MainAPI() {
    override var name = "IPTV · ${account.name}"
    override var mainUrl = account.url.trimEnd('/')
    override var lang = "fr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    private val scheme = "iptv://${account.id}"
    private val mutex = Mutex()
    private var catalog: List<IptvItem>? = null
    private var categories: List<Pair<IptvItem.Kind, String>> = emptyList()
    /** M3U series episodes, by series id. */
    private var m3uEpisodes: Map<String, List<M3uEpisode>> = emptyMap()
    /** Guide address given in the M3U header (url-tvg / x-tvg-url). */
    private var m3uGuideUrl: String? = null

    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val guide = EpgStore(account.id.ifBlank { Integer.toHexString(account.url.hashCode()) })

    private val server get() = mainUrl.substringBefore("/player_api.php").trimEnd('/')

    /** Subscription details, read with the catalog (Xtream only). */
    @Volatile var info: IptvAccountInfo? = null
        private set

    /** Username and password as they go in stream paths. */
    private val pathUser get() = pathEnc(account.username)
    private val pathPass get() = pathEnc(account.password)

    /** Headers every request of this account sends (some panels only answer their usual players). */
    val streamHeaders: Map<String, String>
        get() = account.userAgent.trim().takeIf { it.isNotEmpty() }?.let { mapOf("User-Agent" to it) }.orEmpty()
    private val auth get() = "username=${enc(account.username)}&password=${enc(account.password)}"

    override val mainPage: List<MainPageData>
        get() {
            val pages = mutableListOf(
                MainPageData("Chaînes TV", "recent:LIVE", true),
                MainPageData("Films ajoutés récemment", "recent:MOVIE", false),
                MainPageData("Séries ajoutées récemment", "recent:SERIES", false),
            )
            categories.forEach { (kind, category) ->
                val label = when (kind) {
                    IptvItem.Kind.LIVE -> "TV · $category"
                    IptvItem.Kind.MOVIE -> "Films · $category"
                    IptvItem.Kind.SERIES -> "Séries · $category"
                }
                pages += MainPageData(label, "cat:${kind.name}:$category", kind == IptvItem.Kind.LIVE)
            }
            return pages
        }

    /** Downloads the whole catalog once; called before the provider is shown. */
    suspend fun prepare() {
        catalogOrLoad()
    }

    private suspend fun catalogOrLoad(): List<IptvItem> = mutex.withLock {
        catalog?.let { return it }
        val items = when (account.type) {
            IptvType.XTREAM -> loadXtream()
            IptvType.M3U -> loadM3u()
        }.filter { com.cloudstreampc.core.Parental.nsfwAllowed || !adultPattern.containsMatchIn(it.category) }
        categories = items.map { it.kind to it.category }.distinct()
        catalog = items
        Logger.info("IPTV ${account.name} : ${items.size} éléments, ${categories.size} catégories")
        items
    }

    // ----- Xtream Codes -----

    private suspend fun xtream(action: String, extra: String = ""): JsonNode =
        mapper.readTree(app.get("$server/player_api.php?$auth&action=$action$extra", headers = streamHeaders, timeout = 90).text)

    /**
     * The big lists (channels, movies, series), read one entry at a time: large providers send tens of MB
     * (90 MB of movies seen), more than the HTTP library accepts as text and too much to hold as one tree.
     */
    private suspend fun xtreamEach(action: String, each: (JsonNode) -> Unit) {
        val response = app.get("$server/player_api.php?$auth&action=$action", headers = streamHeaders, timeout = 300)
        response.body.byteStream().use { input ->
            mapper.factory.createParser(input).use { parser ->
                if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) return
                while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                    each(mapper.readTree(parser))
                }
            }
        }
    }

    private suspend fun readAccountInfo(): IptvAccountInfo? = runCatching {
        val root = mapper.readTree(app.get("$server/player_api.php?$auth", headers = streamHeaders, timeout = 30).text)
        parseAccountInfo(root)
    }.onFailure { Logger.info("IPTV ${account.name} : infos du compte illisibles (${it.message})") }.getOrNull()

    private suspend fun loadXtream(): List<IptvItem> {
        val items = mutableListOf<IptvItem>()
        suspend fun categoryNames(action: String) =
            runCatching { xtream(action).items().associate { it.str("category_id").orEmpty() to (it.str("category_name") ?: "Autres") } }
                .getOrDefault(emptyMap())

        info = readAccountInfo()
        info?.let { Logger.info("IPTV ${account.name} : $it") }
        val liveCats = categoryNames("get_live_categories")
        runCatching { xtreamEach("get_live_streams") {
            items += IptvItem(
                IptvItem.Kind.LIVE, it.str("stream_id") ?: return@xtreamEach, it.str("name") ?: return@xtreamEach,
                liveCats[it.str("category_id")] ?: "Autres", it.str("stream_icon"),
                epgId = it.str("epg_channel_id")?.ifBlank { null },
                archiveDays = if (it.str("tv_archive") == "1") it.str("tv_archive_duration")?.toIntOrNull() ?: 0 else 0,
            )
        } }.onFailure { Logger.error("IPTV ${account.name} : liste get_live_streams illisible", it) }
        val vodCats = categoryNames("get_vod_categories")
        runCatching { xtreamEach("get_vod_streams") {
            items += IptvItem(
                IptvItem.Kind.MOVIE, it.str("stream_id") ?: return@xtreamEach, it.str("name") ?: return@xtreamEach,
                vodCats[it.str("category_id")] ?: "Autres", it.str("stream_icon"),
                it.double("rating"), it.str("added")?.toLongOrNull() ?: 0, it.str("container_extension") ?: "mp4",
            )
        } }.onFailure { Logger.error("IPTV ${account.name} : liste get_vod_streams illisible", it) }
        val seriesCats = categoryNames("get_series_categories")
        runCatching { xtreamEach("get_series") {
            items += IptvItem(
                IptvItem.Kind.SERIES, it.str("series_id") ?: return@xtreamEach, it.str("name") ?: return@xtreamEach,
                seriesCats[it.str("category_id")] ?: "Autres", it.str("cover"),
                it.double("rating"), it.str("last_modified")?.toLongOrNull() ?: 0,
            )
        } }.onFailure { Logger.error("IPTV ${account.name} : liste get_series illisible", it) }
        if (items.isEmpty()) error("Aucun contenu : vérifiez l'adresse du serveur, l'identifiant et le mot de passe.")
        return items
    }

    // ----- M3U -----

    private data class M3uEpisode(val name: String, val season: Int?, val episode: Int?, val url: String, val poster: String?)

    private suspend fun loadM3u(): List<IptvItem> {
        // Playlists of big providers are often over the 5 MB the HTTP library reads as plain text.
        val text = app.get(account.url, headers = streamHeaders, timeout = 300).body.string()
        require(text.trimStart().startsWith("#EXTM3U") || text.contains("#EXTINF")) { "Ce lien n'est pas une playlist M3U." }
        val items = mutableListOf<IptvItem>()
        val series = linkedMapOf<String, MutableList<M3uEpisode>>()
        val seriesInfo = mutableMapOf<String, Pair<String, String?>>()
        val episodePattern = Regex("""(?i)^(.*?)[\s._-]*S(\d{1,2})\s*E(\d{1,3})""")
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U") -> m3uGuideUrl =
                    Regex("""(?:url-tvg|x-tvg-url)="([^"]+)"""").find(line)?.groupValues?.get(1)?.split(",")?.firstOrNull()?.trim()
                line.startsWith("#EXTINF") -> info = line
                line.isEmpty() || line.startsWith("#") -> Unit
                info != null -> {
                    val attrs = Regex("""([\w-]+)="([^"]*)"""").findAll(info).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = info.substringAfterLast(",").trim().ifBlank { attrs["tvg-name"] ?: line }
                    val group = attrs["group-title"]?.ifBlank { null } ?: "Autres"
                    val logo = attrs["tvg-logo"]?.ifBlank { null }
                    val id = Integer.toHexString(line.hashCode())
                    val isSeries = line.contains("/series/")
                    val isMovie = line.contains("/movie/") || Regex("(?i)\\.(mp4|mkv|avi)(\\?|$)").containsMatchIn(line)
                    val match = episodePattern.find(name)
                    when {
                        isSeries || (match != null && !line.contains("/live/")) -> {
                            val seriesName = match?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: name
                            val seriesId = Integer.toHexString("$group/$seriesName".hashCode())
                            seriesInfo.putIfAbsent(seriesId, seriesName to logo)
                            series.getOrPut(seriesId) { mutableListOf() } +=
                                M3uEpisode(name, match?.groupValues?.get(2)?.toInt(), match?.groupValues?.get(3)?.toInt(), line, logo)
                            if (series[seriesId]!!.size == 1) {
                                items += IptvItem(IptvItem.Kind.SERIES, seriesId, seriesName, group, logo)
                            }
                        }
                        isMovie -> items += IptvItem(IptvItem.Kind.MOVIE, id, name, group, logo, extra = line)
                        else -> items += IptvItem(
                            IptvItem.Kind.LIVE, id, name, group, logo, extra = line,
                            epgId = attrs["tvg-id"]?.ifBlank { null },
                        )
                    }
                    info = null
                }
            }
        }
        m3uEpisodes = series
        if (items.isEmpty()) error("La playlist est vide.")
        return items
    }

    // ----- MainAPI -----

    private fun IptvItem.toSearchResponse(): SearchResponse {
        val url = "$scheme/${kind.name.lowercase()}/${enc(id)}"
        return when (kind) {
            IptvItem.Kind.LIVE -> newLiveSearchResponse(name, url, TvType.Live, fix = false) { posterUrl = poster }
            IptvItem.Kind.MOVIE -> newMovieSearchResponse(name, url, TvType.Movie, fix = false) {
                posterUrl = poster
                score = rating?.takeIf { it > 0 }?.let { Score.from10(it) }
            }
            IptvItem.Kind.SERIES -> newTvSeriesSearchResponse(name, url, TvType.TvSeries, fix = false) {
                posterUrl = poster
                score = rating?.takeIf { it > 0 }?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = catalogOrLoad()
        val selected = when {
            request.data.startsWith("recent:") -> {
                val kind = IptvItem.Kind.valueOf(request.data.removePrefix("recent:"))
                items.filter { it.kind == kind }.let { list ->
                    if (kind == IptvItem.Kind.LIVE) list else list.sortedByDescending { it.added }
                }
            }
            else -> {
                val (kind, category) = request.data.removePrefix("cat:").split(":", limit = 2)
                items.filter { it.kind.name == kind && it.category == category }
            }
        }
        val pageItems = selected.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return newHomePageResponse(
            HomePageList(request.name, pageItems.map { it.toSearchResponse() }, request.horizontalImages),
            hasNext = selected.size > page * PAGE_SIZE,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.normalized()
        return catalogOrLoad().filter { it.name.normalized().contains(needle) }
            .sortedBy { it.kind.ordinal }
            .take(150)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix("$scheme/")
        val kind = path.substringBefore("/")
        val id = dec(path.substringAfter("/"))
        val item = catalogOrLoad().firstOrNull { it.kind.name.equals(kind, true) && it.id == id }
            ?: error("Contenu introuvable dans l'abonnement IPTV.")
        return when (item.kind) {
            IptvItem.Kind.LIVE -> newLiveStreamLoadResponse(item.name, url, "live|${item.id}") {
                posterUrl = item.poster
                plot = nowAndNext(item) ?: epg(item)
                tags = listOf(item.category)
                recommendations = similar(item)
            }
            IptvItem.Kind.MOVIE -> loadMovie(item, url)
            IptvItem.Kind.SERIES -> loadSeries(item, url)
        }
    }

    /** Other titles of the same category, best rated first: the "Similaires" of IPTV. */
    private suspend fun similar(item: IptvItem): List<SearchResponse> =
        catalogOrLoad().filter { it.kind == item.kind && it.category == item.category && it.id != item.id }
            .sortedWith(compareByDescending<IptvItem> { it.rating ?: 0.0 }.thenByDescending { it.added })
            .take(20)
            .map { it.toSearchResponse() }

    // ----- Guide TV -----

    /** A channel of the guide, with what is needed to open it. */
    class GuideChannel(val name: String, val logo: String?, val category: String, val epgId: String?, val url: String)

    val guideUrl: String?
        get() = when (account.type) {
            IptvType.XTREAM -> "$server/xmltv.php?$auth"
            IptvType.M3U -> m3uGuideUrl
        }

    suspend fun channels(): List<GuideChannel> = catalogOrLoad().filter { it.kind == IptvItem.Kind.LIVE }.map {
        GuideChannel(it.name, it.poster, it.category, it.epgId, it.toSearchResponse().url)
    }

    /** Downloads the XMLTV guide of the account's channels (cached for 12 hours). */
    suspend fun loadGuide(): Map<String, List<Programme>> {
        val ids = catalogOrLoad().mapNotNull { it.epgId?.lowercase() }.toSet()
        return guide.ensure(guideUrl, ids)
    }

    private suspend fun nowAndNext(item: IptvItem): String? {
        if (item.epgId == null) return null
        // The first download can be large: don't hold the channel page for it.
        if (!guide.isLoaded) {
            backgroundScope.launch { runCatching { loadGuide() } }
            return null
        }
        val now = System.currentTimeMillis()
        val current = guide.current(item.epgId, now)
        val next = guide.next(item.epgId, now)
        if (current == null && next == null) return null
        val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(time)
        return buildString {
            current?.let {
                append("En ce moment : ${it.title} (${at(it.start)} – ${at(it.stop)})")
                it.description?.let { d -> append("\n").append(d.take(300)) }
            }
            next?.let { if (isNotEmpty()) append("\n\n"); append("Ensuite : ${it.title} à ${at(it.start)}") }
        }
    }

    private suspend fun epg(item: IptvItem): String? {
        if (account.type != IptvType.XTREAM) return null
        return runCatching {
            xtream("get_short_epg", "&stream_id=${item.id}&limit=3").get("epg_listings").items().mapNotNull { listing ->
                val title = listing.str("title")?.let { String(Base64.getDecoder().decode(it)) } ?: return@mapNotNull null
                val start = listing.str("start")?.substringAfter(" ")?.take(5)
                listOfNotNull(start, title).joinToString("  ")
            }.joinToString("\n").ifBlank { null }
        }.getOrNull()
    }

    private suspend fun loadMovie(item: IptvItem, url: String): LoadResponse {
        if (account.type == IptvType.M3U) {
            val recs = similar(item)
            return newMovieLoadResponse(item.name, url, TvType.Movie, "direct|${item.extra}") {
                posterUrl = item.poster
                tags = listOf(item.category)
                recommendations = recs
            }
        }
        val info = runCatching { xtream("get_vod_info", "&vod_id=${item.id}") }.getOrNull()
        val details = info?.get("info")
        val ext = info?.get("movie_data").str("container_extension") ?: item.extra ?: "mp4"
        val recs = similar(item)
        return newMovieLoadResponse(details.str("name") ?: item.name, url, TvType.Movie, "movie|${item.id}.$ext") {
            posterUrl = details.str("movie_image") ?: item.poster
            backgroundPosterUrl = details?.get("backdrop_path").items().firstOrNull()?.asText()
            plot = details.str("plot") ?: details.str("description")
            year = details.str("releasedate")?.take(4)?.toIntOrNull()
            duration = details.int("duration_secs")?.div(60)
            tags = details.str("genre")?.split(",", "/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(item.category)
            score = (details.double("rating") ?: item.rating)?.takeIf { it > 0 }?.let { Score.from10(it) }
            recommendations = recs
        }
    }

    private suspend fun loadSeries(item: IptvItem, url: String): LoadResponse {
        if (account.type == IptvType.M3U) {
            val episodes = m3uEpisodes[item.id].orEmpty().map { ep ->
                newEpisode("direct|${ep.url}", {
                    name = ep.name
                    season = ep.season
                    episode = ep.episode
                    posterUrl = ep.poster
                }, false)
            }
            val recs = similar(item)
            return newTvSeriesLoadResponse(item.name, url, TvType.TvSeries, episodes) {
                posterUrl = item.poster
                recommendations = recs
            }
        }
        val info = xtream("get_series_info", "&series_id=${item.id}")
        val details = info.get("info")
        val recs = similar(item)
        val episodes = info.get("episodes")?.fields()?.asSequence()?.flatMap { (seasonKey, list) ->
            list.items().map { ep ->
                val epInfo = ep.get("info")
                newEpisode("series|${ep.str("id")}.${ep.str("container_extension") ?: "mp4"}", {
                    name = ep.str("title")
                    season = ep.int("season") ?: seasonKey.toIntOrNull()
                    episode = ep.int("episode_num")
                    posterUrl = epInfo.str("movie_image")
                    description = epInfo.str("plot")
                    runTime = epInfo.int("duration_secs")?.div(60)
                }, false)
            }
        }?.toList().orEmpty()
        return newTvSeriesLoadResponse(details.str("name") ?: item.name, url, TvType.TvSeries, episodes) {
            posterUrl = details.str("cover") ?: item.poster
            backgroundPosterUrl = details?.get("backdrop_path").items().firstOrNull()?.asText()
            plot = details.str("plot")
            year = details.str("releaseDate")?.take(4)?.toIntOrNull()
            tags = details.str("genre")?.split(",", "/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(item.category)
            score = (details.double("rating") ?: item.rating)?.takeIf { it > 0 }?.let { Score.from10(it) }
            recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val kind = data.substringBefore("|")
        val value = data.substringAfter("|")
        val user = pathUser
        val pass = pathPass
        val links: List<Pair<String, String>> = when (kind) {
            "live" -> if (account.type == IptvType.XTREAM) {
                liveFormats().map { format ->
                    (if (format == "m3u8") "HLS" else "MPEG-TS") to "$server/live/$user/$pass/$value.$format"
                }
            } else {
                val item = catalogOrLoad().firstOrNull { it.kind == IptvItem.Kind.LIVE && it.id == value }
                listOfNotNull(item?.extra?.let { "Direct" to it })
            }
            "movie" -> listOf("Film" to "$server/movie/$user/$pass/$value")
            "series" -> listOf("Épisode" to "$server/series/$user/$pass/$value")
            "direct" -> listOf("Direct" to value)
            // Replay of a past programme: catchup|<stream id>|<start epoch s>|<minutes>
            "catchup" -> value.split("|").takeIf { it.size == 3 }?.let { (id, start, minutes) ->
                listOf("Revoir" to catchupUrl(id, start.toLong() * 1000, minutes.toInt()))
            }.orEmpty()
            else -> emptyList()
        }
        links.forEachIndexed { index, (label, link) ->
            val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, "${account.name} · $label", link, type) {
                // In the order the plan prefers (the live relay makes MPEG-TS the steadiest).
                quality = 1080 - index
                headers = streamHeaders
            })
        }
        return links.isNotEmpty()
    }

    /** Live formats to offer, best first: MPEG-TS (continuous, relayed without cuts) then HLS, as the plan allows. */
    private fun liveFormats(): List<String> {
        val allowed = info?.formats.orEmpty().filter { it == "ts" || it == "m3u8" }
        return listOf("ts", "m3u8").filter { allowed.isEmpty() || it in allowed }
    }

    /** Xtream replay address: the start is written in the server's time zone. */
    internal fun catchupUrl(streamId: String, startMs: Long, minutes: Int): String {
        val zone = info?.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        val start = java.time.Instant.ofEpochMilli(startMs).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
        return "$server/timeshift/$pathUser/$pathPass/$minutes/$start/$streamId.ts"
    }

    /** Some channels of this account keep replays. */
    suspend fun hasReplay(): Boolean = account.type == IptvType.XTREAM && catalogOrLoad().any { it.kind == IptvItem.Kind.LIVE && it.archiveDays > 0 }

    /** Replay is possible for this channel and this moment. */
    suspend fun canReplay(channelUrl: String, startMs: Long): Boolean {
        val item = liveItem(channelUrl) ?: return false
        val days = item.archiveDays
        return account.type == IptvType.XTREAM && days > 0 && startMs < System.currentTimeMillis() &&
            System.currentTimeMillis() - startMs < days * 86_400_000L
    }

    /** Data to give [loadLinks] for the replay of a programme. */
    suspend fun replayData(channelUrl: String, startMs: Long, stopMs: Long): String? {
        val item = liveItem(channelUrl) ?: return null
        val minutes = ((stopMs - startMs) / 60_000).toInt().coerceAtLeast(1)
        return "catchup|${item.id}|${startMs / 1000}|$minutes"
    }

    private suspend fun liveItem(channelUrl: String): IptvItem? {
        val path = channelUrl.removePrefix("$scheme/")
        if (!path.startsWith("live/", true)) return null
        val id = dec(path.substringAfter("/"))
        return catalogOrLoad().firstOrNull { it.kind == IptvItem.Kind.LIVE && it.id == id }
    }

    /** This account's way to play a channel known by its guide id or name, if it has it. */
    internal suspend fun sameChannel(epgId: String?, name: String): IptvItem? {
        val channels = catalogOrLoad().filter { it.kind == IptvItem.Kind.LIVE }
        epgId?.lowercase()?.let { id -> channels.firstOrNull { it.epgId?.lowercase() == id }?.let { return it } }
        val wanted = channelKey(name)
        return channels.firstOrNull { channelKey(it.name) == wanted }
    }

    /** Links of a channel of this account (no network: Xtream links are built from the account). */
    internal suspend fun liveLinks(item: IptvItem): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        loadLinks("live|${item.id}", false, {}) { links += it }
        return links
    }

    /**
     * The same channel elsewhere on this account (other quality or category: "BFM TV HD", "BFM TV SD"...).
     * Providers often serve these copies from other servers: when one is down, another usually plays.
     * Same guide id and same name once quality marks are removed, so "+4", replays or "-2H" copies are left out.
     */
    internal suspend fun duplicates(channelUrl: String, max: Int = 3): List<IptvItem> {
        val item = liveItem(channelUrl) ?: return emptyList()
        val key = channelKey(item.name)
        val epg = item.epgId?.lowercase()?.takeIf { it.isNotBlank() }
        return catalogOrLoad().filter {
            it.kind == IptvItem.Kind.LIVE && it.id != item.id && channelKey(it.name) == key &&
                (epg == null || it.epgId?.lowercase() == epg)
        }.take(max)
    }

    /** Channel known by its page address: guide id and name, to find it on other accounts. */
    suspend fun channelIdentity(channelUrl: String): Pair<String?, String>? = liveItem(channelUrl)?.let { it.epgId to it.name }

    /** Channels of the same category, in order, for "chaîne suivante / précédente". */
    suspend fun neighbours(channelUrl: String): List<GuideChannel> {
        val item = liveItem(channelUrl) ?: return emptyList()
        return catalogOrLoad().filter { it.kind == IptvItem.Kind.LIVE && it.category == item.category }
            .map { GuideChannel(it.name, it.poster, it.category, it.epgId, it.toSearchResponse().url) }
    }

    companion object {
        fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
        fun dec(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
        /** Encoding for a path segment (a space is %20, not +). */
        fun pathEnc(value: String): String = enc(value).replace("+", "%20")

        /** "TF1 HD", "FR| TF1 FHD", "|FR| TF1 ᵁᴴᴰ", "TF1 (backup)" → "tf1": to recognize a channel across accounts. */
        fun channelKey(name: String): String = name.lowercase()
            .replace(Regex("""^\s*\|?\s*[a-z]{2,3}\s*[|:-]\s*"""), "")
            .replace(Regex("""\b(fhd|uhd|hd|sd|4k|hevc|h265|1080p?|720p?|backup|direct|live)\b|\(.*?\)|\[.*?]"""), " ")
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
            .replace(Regex("[^a-z0-9+]+"), "")

        internal fun parseAccountInfo(root: JsonNode): IptvAccountInfo? {
            val user = root.get("user_info") ?: return null
            fun text(node: JsonNode?, key: String) = node?.get(key)?.takeUnless { it.isNull }?.asText()?.ifBlank { null }
            return IptvAccountInfo(
                maxConnections = text(user, "max_connections")?.toIntOrNull(),
                activeConnections = text(user, "active_cons")?.toIntOrNull(),
                expiresAt = text(user, "exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
                formats = user.get("allowed_output_formats")?.map { it.asText() }.orEmpty(),
                status = text(user, "status"),
                timezone = text(root.get("server_info"), "timezone"),
            )
        }

        /** Checks an account and returns a short description ("1234 chaînes, 567 films..."). */
        suspend fun test(account: IptvAccount): String {
            val provider = IptvProvider(account)
            val items = provider.catalogOrLoad()
            val count = items.groupingBy { it.kind }.eachCount()
            return "${count[IptvItem.Kind.LIVE] ?: 0} chaînes, ${count[IptvItem.Kind.MOVIE] ?: 0} films, " +
                "${count[IptvItem.Kind.SERIES] ?: 0} séries"
        }
    }
}
