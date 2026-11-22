package com.moncloudi.providers

import com.moncloudi.core.mapper
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Titles of films and series in the interface language, from their IMDb id, without any key:
 * Wikidata gives each work its name in every language ("The Wild Robot" → « Le Robot sauvage »).
 * The French addons know the titles under these names, not under Cinemeta's English ones.
 */
object LocalizedTitles {
    internal var endpoint = "https://query.wikidata.org/sparql"
    /** "language|imdbId" -> title, "" when Wikidata has none. */
    private val cache = ConcurrentHashMap<String, String>()

    /** imdbId -> title in [language], for the ids Wikidata knows. Nothing to do in English. */
    suspend fun of(imdbIds: List<String>, language: String): Map<String, String> {
        if (language == "en" || imdbIds.isEmpty()) return emptyMap()
        val ids = imdbIds.filter { Regex("tt\\d+").matches(it) }.distinct()
        val missing = ids.filter { !cache.containsKey("$language|$it") }
        missing.chunked(60).forEach { chunk ->
            val query = """
                SELECT ?imdb ?label WHERE {
                  VALUES ?imdb { ${chunk.joinToString(" ") { "\"$it\"" }} }
                  ?work wdt:P345 ?imdb ; rdfs:label ?label .
                  FILTER(LANG(?label) = "$language")
                }
            """.trimIndent()
            val url = "$endpoint?format=json&query=" + URLEncoder.encode(query, Charsets.UTF_8)
            val text = app.get(url, headers = mapOf("Accept" to "application/sparql-results+json", "User-Agent" to "MonCloudi/desktop"), timeout = 20).text
            val found = mapper.readTree(text).path("results").path("bindings").associate {
                it.path("imdb").path("value").asText() to it.path("label").path("value").asText()
            }
            chunk.forEach { id -> cache["$language|$id"] = found[id].orEmpty() }
        }
        return ids.mapNotNull { id -> cache["$language|$id"]?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap()
    }

    internal fun clearCache() = cache.clear()
}
