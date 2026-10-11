package com.cloudstreampc.providers

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.StremioAddonEntry
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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A Stremio addon manifest, reduced to what the app uses. */
class StremioAddon(val manifestUrl: String, val manifest: JsonNode) {
    val base = manifestUrl.substringBefore("/manifest.json").trimEnd('/')
    val id: String = manifest.str("id") ?: manifestUrl
    val name: String = manifest.str("name") ?: id

    private val resources: List<Pair<String, JsonNode?>> = manifest.get("resources").items().map {
        if (it.isTextual) it.asText() to null else (it.str("name") ?: "") to it
    }

    /** Whether the addon answers [resource] requests for this type/id (types and id prefixes are optional). */
    fun supports(resource: String, type: String, id: String): Boolean {
        val entry = resources.firstOrNull { it.first == resource } ?: return false
        val node = entry.second
        val types = (node?.get("types") ?: manifest.get("types")).items().map { it.asText() }
        val prefixes = (node?.get("idPrefixes") ?: manifest.get("idPrefixes")).items().map { it.asText() }
        return (types.isEmpty() || type in types) && (prefixes.isEmpty() || prefixes.any { id.startsWith(it) })
    }

    data class Catalog(
        val type: String,
        val id: String,
        val name: String,
        val searchable: Boolean,
        val requiresExtra: Boolean,
        val genres: Boolean = false,
    )

    val catalogs: List<Catalog> = manifest.get("catalogs").items().map { cat ->
        val extras = cat.get("extra").items()
        val legacyRequired = cat.get("extraRequired").items().map { it.asText() }
        val searchable = extras.any { it.str("name") == "search" } ||
            cat.get("extraSupported").items().any { it.asText() == "search" }
        val requires = extras.any { it.get("isRequired")?.asBoolean() == true } || legacyRequired.isNotEmpty()
        val genres = extras.any { it.str("name") == "genre" } || cat.get("genres").items().isNotEmpty()
        Catalog(cat.str("type") ?: "movie", cat.str("id") ?: "", cat.str("name") ?: name, searchable, requires, genres)
    }

    suspend fun get(path: String): JsonNode = mapper.readTree(app.get("$base/$path", timeout = 30).text)

    companion object {
        suspend fun fetch(manifestUrl: String): StremioAddon {
            val url = manifestUrl.trim().replace(Regex("^stremio://"), "https://")
                .let { if (it.endsWith("manifest.json")) it else it.trimEnd('/') + "/manifest.json" }
            return StremioAddon(url, mapper.readTree(app.get(url, timeout = 30).text))
        }
    }
}

/**
 * All Stremio addons as one CloudStream provider: catalogs become home rows,
 * metadata gives the details page, and every stream addon is asked for links.
 */
class StremioProvider(private val addons: List<StremioAddon>) : MainAPI() {
    override var name = "Stremio"
    override var mainUrl = "stremio://"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live, TvType.Anime)

    override val mainPage: List<MainPageData> = addons.flatMap { addon ->
        addon.catalogs.filter { !it.requiresExtra }.map { cat ->
            val label = if (addons.size > 1) "${cat.name} · ${typeLabel(cat.type)} (${addon.name})" else "${cat.name} · ${typeLabel(cat.type)}"
            MainPageData(label, "${addon.id}|${cat.type}|${cat.id}", false)
        }
    }

    private fun typeLabel(type: String) = when (type) {
        "movie" -> "Films"
        "series" -> "Séries"
        "tv", "channel" -> "TV"
        "anime" -> "Animés"
        else -> type
    }

    private fun tvType(type: String) = when (type) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "tv", "channel" -> TvType.Live
        else -> TvType.Others
    }

    private fun addon(id: String) = addons.firstOrNull { it.id == id }

    private fun JsonNode.toSearchResponse(source: StremioAddon): SearchResponse? {
        val id = str("id") ?: return null
        val type = str("type") ?: "movie"
        val title = str("name") ?: return null
        val url = "stremio://$type/${IptvProvider.enc(id)}?addon=${IptvProvider.enc(source.id)}"
        val rating = double("imdbRating")?.takeIf { it > 0 }?.let { Score.from10(it) }
        val year = str("releaseInfo")?.take(4)?.toIntOrNull()
        return when (tvType(type)) {
            TvType.Live -> newLiveSearchResponse(title, url, TvType.Live, fix = false) { posterUrl = str("poster") }
            TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                posterUrl = str("poster"); score = rating; this.year = year
            }
            else -> newTvSeriesSearchResponse(title, url, tvType(type), fix = false) {
                posterUrl = str("poster"); score = rating; this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (addonId, type, catalogId) = request.data.split("|", limit = 3)
        val source = addon(addonId) ?: error("Addon introuvable")
        val skip = (page - 1) * 100
        val path = if (skip > 0) "catalog/$type/$catalogId/skip=$skip.json" else "catalog/$type/$catalogId.json"
        val metas = source.get(path).get("metas").items()
        return newHomePageResponse(
            HomePageList(request.name, metas.mapNotNull { it.toSearchResponse(source) }, false),
            hasNext = metas.size >= 50,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        addons.flatMap { addon -> addon.catalogs.filter { it.searchable }.map { addon to it } }
            .map { (addon, cat) ->
                async {
                    runCatching {
                        addon.get("catalog/${cat.type}/${cat.id}/search=${IptvProvider.enc(query)}.json")
                            .get("metas").items().mapNotNull { it.toSearchResponse(addon) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.url.substringBefore("?") }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix("stremio://")
        val type = path.substringBefore("/")
        val id = IptvProvider.dec(path.substringAfter("/").substringBefore("?"))
        val preferred = addon(IptvProvider.dec(url.substringAfter("addon=", "")))
        val sources = listOfNotNull(preferred) + addons.filter { it != preferred }
        val meta = sources.filter { it.supports("meta", type, id) }.firstNotNullOfOrNull { addon ->
            runCatching { addon.get("meta/$type/${IptvProvider.enc(id)}.json").get("meta") }.getOrNull()?.takeIf { !it.isNull }
        } ?: error("Aucun addon ne fournit la fiche de ce titre (ajoutez Cinemeta).")

        val title = meta.str("name") ?: id
        val poster = meta.str("poster")
        val background = meta.str("background")
        val description = meta.str("description")
        val year = meta.str("releaseInfo")?.take(4)?.toIntOrNull() ?: meta.str("year")?.take(4)?.toIntOrNull()
        val genres = meta.get("genres").items().map { it.asText() }.ifEmpty { meta.get("genre").items().map { it.asText() } }
        val rating = meta.double("imdbRating")?.takeIf { it > 0 }?.let { Score.from10(it) }
        val runtime = meta.str("runtime")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val videos = meta.get("videos").items()
        val recs = similar(type, id, genres, preferred)

        return when {
            tvType(type) == TvType.Live -> newLiveStreamLoadResponse(title, url, "$type|$id") {
                posterUrl = poster; plot = description
            }
            videos.isEmpty() || type == "movie" -> newMovieLoadResponse(title, url, tvType(type), "$type|$id") {
                posterUrl = poster; backgroundPosterUrl = background; plot = description
                this.year = year; tags = genres; score = rating; duration = runtime
                recommendations = recs
            }
            else -> {
                val episodes = videos.sortedWith(compareBy({ it.int("season") ?: 0 }, { it.int("episode") ?: it.int("number") ?: 0 }))
                    .filter { (it.int("season") ?: 1) > 0 } // season 0 = specials
                    .map { video ->
                        newEpisode("$type|${video.str("id")}", {
                            name = video.str("name") ?: video.str("title")
                            season = video.int("season")
                            episode = video.int("episode") ?: video.int("number")
                            posterUrl = video.str("thumbnail")
                            this.description = video.str("overview") ?: video.str("description")
                        }, false)
                    }
                newTvSeriesLoadResponse(title, url, tvType(type), episodes) {
                    posterUrl = poster; backgroundPosterUrl = background; plot = description
                    this.year = year; tags = genres; score = rating
                    recommendations = recs
                }
            }
        }
    }

    /** Titles of the same genre from a catalog that can filter by genre (e.g. Cinemeta). */
    private suspend fun similar(type: String, id: String, genres: List<String>, preferred: StremioAddon?): List<SearchResponse> {
        val genre = genres.firstOrNull() ?: return emptyList()
        val sources = listOfNotNull(preferred) + addons.filter { it != preferred }
        for (addon in sources) {
            val catalog = addon.catalogs.firstOrNull { it.type == type && it.genres } ?: continue
            val metas = runCatching {
                addon.get("catalog/$type/${catalog.id}/genre=${IptvProvider.enc(genre)}.json").get("metas").items()
            }.getOrNull() ?: continue
            return metas.filter { it.str("id") != id }.take(20).mapNotNull { it.toSearchResponse(addon) }
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val type = data.substringBefore("|")
        val id = data.substringAfter("|")
        val encodedId = IptvProvider.enc(id)
        val subtitleJobs = addons.filter { it.supports("subtitles", type, id) }.map { addon ->
            async {
                runCatching {
                    addon.get("subtitles/$type/$encodedId.json").get("subtitles").items().forEach { sub ->
                        val url = sub.str("url") ?: return@forEach
                        subtitleCallback(newSubtitleFile(sub.str("lang") ?: "?", url))
                    }
                }
            }
        }
        val streamJobs = addons.filter { it.supports("stream", type, id) }.map { addon ->
            async {
                runCatching { addon.get("stream/$type/$encodedId.json").get("streams").items() }
                    .onFailure { Logger.error("Stremio ${addon.name} : flux indisponibles", it) }
                    .getOrDefault(emptyList())
                    .forEach { stream -> emitStream(addon, stream, subtitleCallback, callback) }
            }
        }
        (subtitleJobs + streamJobs).awaitAll()
        true
    }

    private suspend fun emitStream(
        addon: StremioAddon,
        stream: JsonNode,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val label = listOfNotNull(stream.str("name"), stream.str("title") ?: stream.str("description"))
            .joinToString(" · ").replace('\n', ' ').take(120)
        val text = label.lowercase()
        val quality = when {
            "2160" in text || "4k" in text -> Qualities.P2160.value
            "1080" in text -> Qualities.P1080.value
            "720" in text -> Qualities.P720.value
            "480" in text -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
        stream.get("subtitles").items().forEach { sub ->
            sub.str("url")?.let { subtitleCallback(newSubtitleFile(sub.str("lang") ?: "?", it)) }
        }
        val url = stream.str("url")
        val infoHash = stream.str("infoHash")
        when {
            url != null -> callback(newExtractorLink(addon.name, label.ifBlank { addon.name }, url,
                if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = quality
                headers = stream.get("behaviorHints")?.get("proxyHeaders")?.get("request")?.fields()?.asSequence()
                    ?.associate { it.key to it.value.asText() }.orEmpty()
            })
            // Torrents need a torrent engine: listed but not playable (like the app without a torrent server).
            infoHash != null -> callback(newExtractorLink(addon.name, "$label (torrent)", "magnet:?xt=urn:btih:$infoHash", ExtractorLinkType.MAGNET) {
                this.quality = quality
            })
        }
    }

    companion object {
        val presets = listOf(
            "Cinemeta (catalogues films et séries)" to "https://v3-cinemeta.strem.io/manifest.json",
            "OpenSubtitles (sous-titres)" to "https://opensubtitles-v3.strem.io/manifest.json",
        )

        suspend fun create(entries: List<StremioAddonEntry>): StremioProvider? {
            val addons = coroutineScope {
                entries.filter { it.enabled }.map { entry ->
                    async {
                        runCatching { StremioAddon.fetch(entry.manifestUrl) }
                            .onFailure { Logger.error("Addon Stremio ${entry.manifestUrl}", it) }
                            .getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            return if (addons.isEmpty()) null else StremioProvider(addons)
        }
    }
}
