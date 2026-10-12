package com.cloudstreampc.sync

import com.cloudstreampc.core.TrackerAccount
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.delay

/** Trakt: login with a code typed on trakt.tv (device flow), history through the sync API. */
object TraktTracker : Tracker {
    override val id = "trakt"
    override val name = "Trakt"
    override val developerUrl = "https://trakt.tv/oauth/applications/new"
    override val setupSteps = listOf(
        "Sur Trakt, ouvrez Settings → Your API Apps → « New Application ».",
        "Nom : CloudStreamPC. Redirect uri : urn:ietf:wg:oauth:2.0:oob",
        "Enregistrez, puis copiez le « Client ID » et le « Client Secret » ci-dessous.",
    )
    override val needsSecret = true
    override val animeOnly = false

    internal var apiBase = "https://api.trakt.tv"
    internal var pollDelayOverride: Long? = null

    private fun headers(account: TrackerAccount) = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", account.clientId.trim())
        account.accessToken?.let { put("Authorization", "Bearer $it") }
    }

    private fun withTokens(account: TrackerAccount, body: JsonNode) = account.copy(
        accessToken = body.path("access_token").asText().ifBlank { null } ?: error("Trakt n'a pas donné de jeton."),
        refreshToken = body.path("refresh_token").asText().ifBlank { null } ?: account.refreshToken,
        expiresAt = (body.path("created_at").asLong(System.currentTimeMillis() / 1000) + body.path("expires_in").asLong(86_400)) * 1000,
    )

    override suspend fun login(account: TrackerAccount, ui: LoginUi): TrackerAccount {
        val clean = account.copy(clientId = account.clientId.trim(), clientSecret = account.clientSecret.trim(), accessToken = null)
        require(clean.clientId.isNotEmpty() && clean.clientSecret.isNotEmpty()) { "Indiquez le Client ID et le Client Secret de votre application Trakt." }
        val start = Http.expect("POST", "$apiBase/oauth/device/code", headers(clean), Http.jsonBody(mapOf("client_id" to clean.clientId)))
        ui.showCode(start.path("user_code").asText(), start.path("verification_url").asText().ifBlank { "https://trakt.tv/activate" })
        var interval = start.path("interval").asLong(5) * 1000
        val deadline = System.currentTimeMillis() + start.path("expires_in").asLong(600) * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(pollDelayOverride ?: interval)
            val answer = Http.call(
                "POST", "$apiBase/oauth/device/token", headers(clean),
                Http.jsonBody(mapOf("code" to start.path("device_code").asText(), "client_id" to clean.clientId, "client_secret" to clean.clientSecret)),
            )
            when (answer.code) {
                200 -> {
                    val logged = withTokens(clean, answer.body)
                    val me = Http.call("GET", "$apiBase/users/settings", headers(logged))
                    return logged.copy(userName = me.body.path("user").path("username").asText().ifBlank { null })
                }
                400 -> Unit // not yet approved
                429 -> interval += 1000
                404 -> error("Code invalide : vérifiez le Client ID.")
                409 -> error("Ce code a déjà été utilisé.")
                410 -> error("Le code a expiré : recommencez.")
                418 -> error("Connexion refusée sur Trakt.")
                else -> error("Trakt : erreur ${answer.code}")
            }
        }
        error("Le code a expiré : recommencez.")
    }

    private suspend fun refreshed(account: TrackerAccount): TrackerAccount? {
        if (!Trackers.expiresSoon(account) || account.refreshToken == null) return null
        val body = Http.expect(
            "POST", "$apiBase/oauth/token", headers(account.copy(accessToken = null)),
            Http.jsonBody(
                mapOf(
                    "refresh_token" to account.refreshToken, "client_id" to account.clientId, "client_secret" to account.clientSecret,
                    "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob", "grant_type" to "refresh_token",
                ),
            ),
        )
        return withTokens(account, body)
    }

    override suspend fun markWatched(account: TrackerAccount, event: WatchEvent): TrackerAccount? {
        val fresh = refreshed(account)
        val item = buildMap<String, Any> {
            put("title", event.title)
            event.year?.let { put("year", it) }
            event.imdbId?.let { put("ids", mapOf("imdb" to it)) }
            if (!event.isMovie && event.episode != null) {
                put("seasons", listOf(mapOf("number" to (event.season ?: 1), "episodes" to listOf(mapOf("number" to event.episode)))))
            }
        }
        val body = if (event.isMovie) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
        val answer = Http.expect("POST", "$apiBase/sync/history", headers(fresh ?: account), Http.jsonBody(body))
        val added = answer.path("added").let { it.path("movies").asInt(0) + it.path("episodes").asInt(0) }
        if (added == 0) error("titre introuvable sur Trakt")
        return fresh
    }

    override suspend fun list(account: TrackerAccount): List<TrackedTitle> {
        val current = refreshed(account)?.also { Trackers.save(this, it) } ?: account
        return Http.expect("GET", "$apiBase/sync/watchlist?sort=added,desc", headers(current)).mapNotNull { entry ->
            val type = entry.path("type").asText()
            val media = entry.path(type).takeIf { type == "movie" || type == "show" } ?: return@mapNotNull null
            TrackedTitle(media.path("title").asText(), null, if (type == "movie") "Film à voir" else "Série à voir", media.path("year").asInt(0).takeIf { it > 0 })
        }
    }

    /** Trakt only has a watchlist: "À voir" adds to it, the other lists take the title out of it. */
    override suspend fun setStatus(account: TrackerAccount, event: WatchEvent, status: com.cloudstreampc.core.WatchStatus): TrackerAccount? {
        val fresh = refreshed(account)
        val item = buildMap<String, Any> {
            put("title", event.title)
            event.year?.let { put("year", it) }
            event.imdbId?.let { put("ids", mapOf("imdb" to it)) }
        }
        val body = if (event.isMovie) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
        val path = if (status == com.cloudstreampc.core.WatchStatus.PLAN_TO_WATCH) "sync/watchlist" else "sync/watchlist/remove"
        Http.expect("POST", "$apiBase/$path", headers(fresh ?: account), Http.jsonBody(body))
        return fresh
    }
}
