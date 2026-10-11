package com.cloudstreampc.player

import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.mapper
import com.cloudstreampc.core.readJsonFile
import com.cloudstreampc.core.writeJsonFile
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** A part of an episode the player offers to skip: "op" (opening), "recap" or "ed" (credits). */
data class SkipSegment(val kind: String, val start: Double, val stop: Double)

/**
 * Opening / credits times of anime episodes, from AniSkip (like CloudStream).
 * The MyAnimeList id comes from the extension, or from an AniList search on the title.
 */
object SkipSegments {
    internal var aniSkipBase = "https://api.aniskip.com"
    internal var aniListBase = "https://graphql.anilist.co"

    private val cacheFile: File get() = File(AppDirs.cache, "malids.json")
    private val malIds: MutableMap<String, Int> by lazy { readJsonFile<MutableMap<String, Int>>(cacheFile) { mutableMapOf() } }

    internal fun clearCache() {
        synchronized(malIds) { malIds.clear() }
        cacheFile.delete()
    }

    fun isAnime(response: LoadResponse) =
        response is AnimeLoadResponse || response.type in setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)

    suspend fun find(response: LoadResponse, playable: Playable): List<SkipSegment> {
        if (!isAnime(response)) return emptyList()
        val episode = playable.episode ?: if (response.type == TvType.AnimeMovie) 1 else return emptyList()
        return try {
            val malId = malId(response, playable.season) ?: return emptyList()
            aniSkip(malId, episode)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logger.info("AniSkip indisponible pour ${response.name} : ${t.message}")
            emptyList()
        }
    }

    internal suspend fun aniSkip(malId: Int, episode: Int): List<SkipSegment> {
        val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("") { "&types%5B%5D=$it" }
        val url = "$aniSkipBase/v2/skip-times/$malId/$episode?episodeLength=0$types"
        val node = mapper.readTree(app.get(url, timeout = 8).text)
        if (!node.path("found").asBoolean()) return emptyList()
        return node.path("results").mapNotNull { result ->
            val kind = when (result.path("skipType").asText()) {
                "op", "mixed-op" -> "op"
                "ed", "mixed-ed" -> "ed"
                "recap" -> "recap"
                else -> return@mapNotNull null
            }
            val start = result.path("interval").path("startTime").asDouble(-1.0)
            val stop = result.path("interval").path("endTime").asDouble(-1.0)
            if (start < 0 || stop <= start) null else SkipSegment(kind, start, stop)
        }.sortedBy { it.start }
    }

    internal suspend fun malId(response: LoadResponse, season: Int?): Int? {
        // `addMalId` stores the id as is under the MAL prefix (`getMalId` expects another format here).
        response.syncData[LoadResponse.malIdPrefix]?.trim()?.toIntOrNull()?.let { return it }
        val key = Recommender.normalizedTitle(response.name) + if ((season ?: 1) > 1) " s$season" else ""
        synchronized(malIds) { malIds[key] }?.let { return it.takeIf { id -> id > 0 } }
        val found = searchAniList(response.name, season, response.year)
        synchronized(malIds) {
            malIds[key] = found ?: -1
            runCatching { writeJsonFile(cacheFile, malIds) }
        }
        return found
    }

    /** Best MyAnimeList match for a title (and season, which is a separate entry on MAL). */
    private suspend fun searchAniList(name: String, season: Int?, year: Int?): Int? {
        val wanted = Recommender.normalizedTitle(name)
        val laterSeason = season != null && season > 1
        val query = """query(${'$'}s: String) { Page(perPage: 10) { media(search: ${'$'}s, type: ANIME) {
            idMal seasonYear title { romaji english native } synonyms } } }"""
        val body = mapper.writeValueAsString(
            mapOf("query" to query, "variables" to mapOf("s" to if (laterSeason) "$name season $season" else name)),
        ).toRequestBody("application/json".toMediaType())
        val results = mapper.readTree(app.post(aniListBase, requestBody = body, timeout = 10).text)
            .path("data").path("Page").path("media").toList()
            .filter { it.path("idMal").asInt(0) > 0 }
        fun titles(node: JsonNode) =
            (node.path("title").map { it.asText() } + node.path("synonyms").map { it.asText() })
                .filter { it.isNotBlank() && it != "null" }.map(Recommender::normalizedTitle)

        val match = if (laterSeason) {
            val markers = listOf("season $season", "${season}nd season", "${season}rd season", "${season}th season", "part $season")
            results.firstOrNull { node ->
                titles(node).any { title -> title.startsWith(wanted) && (markers.any(title::contains) || title.endsWith(" $season")) }
            }
        } else {
            results.firstOrNull { node -> titles(node).any { it == wanted } && (year == null || node.path("seasonYear").asInt(year) == year) }
                ?: results.firstOrNull { node -> titles(node).any { it == wanted } }
        }
        return match?.path("idMal")?.asInt()?.takeIf { it > 0 }
    }
}
