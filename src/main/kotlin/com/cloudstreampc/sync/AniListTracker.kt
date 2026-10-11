package com.cloudstreampc.sync

import com.cloudstreampc.core.TrackerAccount
import com.fasterxml.jackson.databind.JsonNode

/** AniList: login in the browser (implicit grant), progress through the GraphQL API. */
object AniListTracker : Tracker {
    override val id = "anilist"
    override val name = "AniList"
    override val developerUrl = "https://anilist.co/settings/developer"
    override val setupSteps = listOf(
        "Sur AniList, ouvrez Paramètres → Développeur et cliquez sur « Create New Client ».",
        "Nom : CloudStreamPC. Redirect URL : ${OAuthLoopback.REDIRECT_URL}",
        "Enregistrez, puis copiez le « Client ID » (un nombre) ci-dessous.",
    )
    override val needsSecret = false
    override val animeOnly = true

    internal var apiUrl = "https://graphql.anilist.co"
    internal var authorizeBase = "https://anilist.co/api/v2/oauth/authorize"

    private suspend fun graphql(token: String?, query: String, variables: Map<String, Any?> = emptyMap()): JsonNode {
        val headers = buildMap {
            put("Accept", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val body = Http.expect("POST", apiUrl, headers, Http.jsonBody(mapOf("query" to query, "variables" to variables)))
        body.path("errors").firstOrNull()?.let { throw ApiException(400, "AniList : ${it.path("message").asText()}") }
        return body.path("data")
    }

    override suspend fun login(account: TrackerAccount, ui: LoginUi): TrackerAccount {
        val clientId = account.clientId.trim()
        require(clientId.isNotEmpty()) { "Indiquez le Client ID de votre client AniList." }
        val answer = OAuthLoopback.authorize("$authorizeBase?client_id=$clientId&response_type=token", ui::openBrowser)
        val token = answer["access_token"] ?: error(answer["error_description"] ?: answer["error"] ?: "AniList n'a pas donné de jeton.")
        val expires = answer["expires_in"]?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 } ?: 0
        val viewer = graphql(token, "query { Viewer { name } }").path("Viewer").path("name").asText().ifBlank { null }
        return account.copy(clientId = clientId, accessToken = token, expiresAt = expires, userName = viewer)
    }

    /** AniList id and episode count of an anime, from its MyAnimeList id or its title. */
    private suspend fun findMedia(token: String, event: WatchEvent): JsonNode? {
        val fields = "id episodes mediaListEntry { progress status }"
        if (event.malId != null) {
            graphql(token, "query(\$id: Int) { Media(idMal: \$id, type: ANIME) { $fields } }", mapOf("id" to event.malId))
                .path("Media").takeIf { !it.isMissingNode && !it.isNull }?.let { return it }
        }
        val search = if ((event.season ?: 1) > 1) "${event.title} season ${event.season}" else event.title
        return graphql(token, "query(\$s: String) { Media(search: \$s, type: ANIME) { $fields } }", mapOf("s" to search))
            .path("Media").takeIf { !it.isMissingNode && !it.isNull }
    }

    override suspend fun markWatched(account: TrackerAccount, event: WatchEvent): TrackerAccount? {
        val token = account.accessToken ?: return null
        val media = findMedia(token, event) ?: error("anime introuvable sur AniList")
        val episode = event.episode ?: 1
        val current = media.path("mediaListEntry").path("progress").asInt(0)
        if (episode <= current) return null
        val total = media.path("episodes").asInt(0).takeIf { it > 0 } ?: event.totalEpisodes
        val status = if (total != null && episode >= total) "COMPLETED" else "CURRENT"
        graphql(
            token,
            "mutation(\$id: Int, \$p: Int, \$s: MediaListStatus) { SaveMediaListEntry(mediaId: \$id, progress: \$p, status: \$s) { id } }",
            mapOf("id" to media.path("id").asInt(), "p" to episode, "s" to status),
        )
        return null
    }

    override suspend fun list(account: TrackerAccount): List<TrackedTitle> {
        val token = account.accessToken ?: return emptyList()
        val userId = graphql(token, "query { Viewer { id } }").path("Viewer").path("id").asInt()
        val data = graphql(
            token,
            """query(${'$'}u: Int) { MediaListCollection(userId: ${'$'}u, type: ANIME, status_in: [CURRENT, PLANNING]) {
                lists { entries { progress status media { title { romaji english } coverImage { large } episodes seasonYear } } } } }""",
            mapOf("u" to userId),
        )
        return data.path("MediaListCollection").path("lists").flatMap { it.path("entries") }.map { entry ->
            val media = entry.path("media")
            val title = media.path("title").path("english").asText().takeIf { it.isNotBlank() && it != "null" }
                ?: media.path("title").path("romaji").asText()
            val total = media.path("episodes").asInt(0)
            val detail = if (entry.path("status").asText() == "PLANNING") "À voir" else
                "Épisode ${entry.path("progress").asInt()}" + if (total > 0) " / $total" else ""
            TrackedTitle(title, media.path("coverImage").path("large").asText().ifBlank { null }, detail, media.path("seasonYear").asInt(0).takeIf { it > 0 })
        }
    }
}
