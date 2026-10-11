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
) {
    enum class Kind { LIVE, MOVIE, SERIES }
}

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

    private val server get() = mainUrl.substringBefore("/player_api.php").trimEnd('/')
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
        }.filter { Store.settings.nsfw || !adultPattern.containsMatchIn(it.category) }
        categories = items.map { it.kind to it.category }.distinct()
        catalog = items
        Logger.info("IPTV ${account.name} : ${items.size} éléments, ${categories.size} catégories")
        items
    }

    // ----- Xtream Codes -----

    private suspend fun xtream(action: String, extra: String = ""): JsonNode =
        mapper.readTree(app.get("$server/player_api.php?$auth&action=$action$extra", timeout = 90).text)

    private suspend fun loadXtream(): List<IptvItem> {
        val items = mutableListOf<IptvItem>()
        suspend fun categoryNames(action: String) =
            runCatching { xtream(action).items().associate { it.str("category_id").orEmpty() to (it.str("category_name") ?: "Autres") } }
                .getOrDefault(emptyMap())

        val liveCats = categoryNames("get_live_categories")
        runCatching { xtream("get_live_streams") }.getOrNull().items().forEach {
            items += IptvItem(
                IptvItem.Kind.LIVE, it.str("stream_id") ?: return@forEach, it.str("name") ?: return@forEach,
                liveCats[it.str("category_id")] ?: "Autres", it.str("stream_icon"),
            )
        }
        val vodCats = categoryNames("get_vod_categories")
        runCatching { xtream("get_vod_streams") }.getOrNull().items().forEach {
            items += IptvItem(
                IptvItem.Kind.MOVIE, it.str("stream_id") ?: return@forEach, it.str("name") ?: return@forEach,
                vodCats[it.str("category_id")] ?: "Autres", it.str("stream_icon"),
                it.double("rating"), it.str("added")?.toLongOrNull() ?: 0, it.str("container_extension") ?: "mp4",
            )
        }
        val seriesCats = categoryNames("get_series_categories")
        runCatching { xtream("get_series") }.getOrNull().items().forEach {
            items += IptvItem(
                IptvItem.Kind.SERIES, it.str("series_id") ?: return@forEach, it.str("name") ?: return@forEach,
                seriesCats[it.str("category_id")] ?: "Autres", it.str("cover"),
                it.double("rating"), it.str("last_modified")?.toLongOrNull() ?: 0,
            )
        }
        if (items.isEmpty()) error("Aucun contenu : vérifiez l'adresse du serveur, l'identifiant et le mot de passe.")
        return items
    }

    // ----- M3U -----

    private data class M3uEpisode(val name: String, val season: Int?, val episode: Int?, val url: String, val poster: String?)

    private suspend fun loadM3u(): List<IptvItem> {
        val text = app.get(account.url, timeout = 120).text
        require(text.trimStart().startsWith("#EXTM3U") || text.contains("#EXTINF")) { "Ce lien n'est pas une playlist M3U." }
        val items = mutableListOf<IptvItem>()
        val series = linkedMapOf<String, MutableList<M3uEpisode>>()
        val seriesInfo = mutableMapOf<String, Pair<String, String?>>()
        val episodePattern = Regex("""(?i)^(.*?)[\s._-]*S(\d{1,2})\s*E(\d{1,3})""")
        var info: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
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
                        else -> items += IptvItem(IptvItem.Kind.LIVE, id, name, group, logo, extra = line)
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
                plot = epg(item)
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
        val user = account.username
        val pass = account.password
        val links: List<Pair<String, String>> = when (kind) {
            "live" -> if (account.type == IptvType.XTREAM) {
                listOf("HLS" to "$server/live/$user/$pass/$value.m3u8", "MPEG-TS" to "$server/live/$user/$pass/$value.ts")
            } else {
                val item = catalogOrLoad().firstOrNull { it.kind == IptvItem.Kind.LIVE && it.id == value }
                listOfNotNull(item?.extra?.let { "Direct" to it })
            }
            "movie" -> listOf("Film" to "$server/movie/$user/$pass/$value")
            "series" -> listOf("Épisode" to "$server/series/$user/$pass/$value")
            "direct" -> listOf("Direct" to value)
            else -> emptyList()
        }
        links.forEach { (label, link) ->
            val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, "${account.name} · $label", link, type) {
                // Live HLS first: it adapts to the connection.
                quality = if (label == "HLS") 1080 else 720
            })
        }
        return links.isNotEmpty()
    }

    companion object {
        fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
        fun dec(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)

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
