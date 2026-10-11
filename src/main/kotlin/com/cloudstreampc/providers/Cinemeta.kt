package com.cloudstreampc.providers

import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.mapper
import com.cloudstreampc.core.readJsonFile
import com.cloudstreampc.core.writeJsonFile
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * Stremio's Cinemeta catalog (no account needed): finds the IMDb entry of a title from any
 * extension, for trailers, subtitles, tracking services and richer details pages.
 */
object Cinemeta {
    internal var base = "https://v3-cinemeta.strem.io"

    private val cacheFile: File get() = File(AppDirs.cache, "imdb-ids.json")
    /** "title|year|type" -> IMDb id, or "" when there is none. */
    private val ids: MutableMap<String, String> by lazy { readJsonFile<MutableMap<String, String>>(cacheFile) { mutableMapOf() } }
    private val metas = mutableMapOf<String, JsonNode>()

    internal fun clearCache() {
        synchronized(ids) { ids.clear() }
        synchronized(metas) { metas.clear() }
        cacheFile.delete()
    }

    fun typeOf(response: LoadResponse) = if (Recommender.family(response.type.name) == "movie") "movie" else "series"

    fun cleanTitle(name: String) = name.replace(Regex("""\s*[\[(](VF|VOSTFR|FRENCH|MULTI|TRUEFRENCH)[])]""", RegexOption.IGNORE_CASE), "").trim()

    /** IMDb id ("tt…") of the title, or null when Cinemeta has no clear match. Errors are thrown (not cached). */
    suspend fun imdbId(response: LoadResponse): String? {
        val type = typeOf(response)
        val key = Recommender.normalizedTitle(response.name) + "|" + (response.year ?: "") + "|" + type
        synchronized(ids) { ids[key] }?.let { return it.ifEmpty { null } }
        val found = withContext(Dispatchers.IO) { search(cleanTitle(response.name), response.year, type) }
        synchronized(ids) {
            ids[key] = found.orEmpty()
            runCatching { writeJsonFile(cacheFile, ids) }
        }
        return found
    }

    private suspend fun search(name: String, year: Int?, type: String): String? {
        val result = mapper.readTree(app.get("$base/catalog/$type/top/search=${URLEncoder.encode(name, Charsets.UTF_8)}.json", timeout = 15).text)
        val wanted = Recommender.normalizedTitle(name)
        val found = result.path("metas").toList()
        fun yearOf(node: JsonNode) = node.path("releaseInfo").asText().take(4).toIntOrNull()
        val sameTitle = found.filter { Recommender.normalizedTitle(it.path("name").asText()) == wanted }
        val meta = sameTitle.firstOrNull { year == null || yearOf(it) == null || yearOf(it) == year }
            ?: found.singleOrNull { year != null && yearOf(it) == year }
            ?: return null
        return meta.path("id").asText().takeIf { it.startsWith("tt") }
    }

    /** Full Cinemeta entry (cast, episodes with pictures, trailers, logo…). */
    suspend fun meta(type: String, imdbId: String): JsonNode? {
        val key = "$type/$imdbId"
        synchronized(metas) { metas[key] }?.let { return it }
        val node = withContext(Dispatchers.IO) {
            mapper.readTree(app.get("$base/meta/$type/$imdbId.json", timeout = 15).text).path("meta")
        }.takeIf { !it.isMissingNode && it.size() > 0 } ?: return null
        synchronized(metas) { metas[key] = node }
        return node
    }

    suspend fun meta(response: LoadResponse): JsonNode? {
        val id = imdbId(response) ?: return null
        return meta(typeOf(response), id)
    }
}
