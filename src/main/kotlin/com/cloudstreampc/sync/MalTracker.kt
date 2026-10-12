package com.cloudstreampc.sync

import com.cloudstreampc.core.TrackerAccount
import java.net.URLEncoder
import java.security.SecureRandom

/** MyAnimeList: OAuth 2 with PKCE in the browser, progress through the v2 API. */
object MalTracker : Tracker {
    override val id = "mal"
    override val name = "MyAnimeList"
    override val developerUrl = "https://myanimelist.net/apiconfig/create"
    override val setupSteps = listOf(
        "Sur MyAnimeList, ouvrez Profil → Account Settings → API → « Create ID ».",
        "App Type : other. App Redirect URL : ${OAuthLoopback.REDIRECT_URL}",
        "Remplissez les champs obligatoires, enregistrez, puis copiez le « Client ID » ci-dessous.",
    )
    override val needsSecret = false
    override val animeOnly = true

    internal var authBase = "https://myanimelist.net/v1/oauth2"
    internal var apiBase = "https://api.myanimelist.net/v2"

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun randomToken(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }

    private fun withTokens(account: TrackerAccount, body: com.fasterxml.jackson.databind.JsonNode) = account.copy(
        accessToken = body.path("access_token").asText().ifBlank { null } ?: error("MyAnimeList n'a pas donné de jeton."),
        refreshToken = body.path("refresh_token").asText().ifBlank { null } ?: account.refreshToken,
        expiresAt = System.currentTimeMillis() + body.path("expires_in").asLong(2_592_000) * 1000,
    )

    private fun credentials(account: TrackerAccount) = buildMap {
        put("client_id", account.clientId.trim())
        if (account.clientSecret.isNotBlank()) put("client_secret", account.clientSecret.trim())
    }

    override suspend fun login(account: TrackerAccount, ui: LoginUi): TrackerAccount {
        val clientId = account.clientId.trim()
        require(clientId.isNotEmpty()) { "Indiquez le Client ID de votre application MyAnimeList." }
        // PKCE "plain": MyAnimeList only supports this method.
        val verifier = randomToken(96)
        val state = randomToken(16)
        val url = "$authBase/authorize?response_type=code&client_id=${enc(clientId)}&code_challenge=$verifier" +
            "&code_challenge_method=plain&state=$state&redirect_uri=${enc(OAuthLoopback.REDIRECT_URL)}"
        val answer = OAuthLoopback.authorize(url, ui::openBrowser)
        require(answer["state"] == state) { "Réponse de connexion inattendue." }
        val code = answer["code"] ?: error(answer["message"] ?: answer["error"] ?: "Connexion annulée.")
        val tokens = Http.expect(
            "POST", "$authBase/token",
            body = Http.formBody(
                credentials(account) + mapOf(
                    "code" to code, "code_verifier" to verifier, "grant_type" to "authorization_code",
                    "redirect_uri" to OAuthLoopback.REDIRECT_URL,
                ),
            ),
        )
        val logged = withTokens(account.copy(clientId = clientId), tokens)
        val me = Http.expect("GET", "$apiBase/users/@me", auth(logged))
        return logged.copy(userName = me.path("name").asText().ifBlank { null })
    }

    private fun auth(account: TrackerAccount) = mapOf("Authorization" to "Bearer ${account.accessToken}")

    private suspend fun refreshed(account: TrackerAccount): TrackerAccount? {
        if (!Trackers.expiresSoon(account) || account.refreshToken == null) return null
        val tokens = Http.expect(
            "POST", "$authBase/token",
            body = Http.formBody(credentials(account) + mapOf("grant_type" to "refresh_token", "refresh_token" to account.refreshToken)),
        )
        return withTokens(account, tokens)
    }

    override suspend fun markWatched(account: TrackerAccount, event: WatchEvent): TrackerAccount? {
        val fresh = refreshed(account)
        val current = fresh ?: account
        val malId = event.malId ?: error("identifiant MyAnimeList inconnu")
        val anime = Http.expect("GET", "$apiBase/anime/$malId?fields=num_episodes,my_list_status", auth(current))
        val episode = event.episode ?: 1
        val watched = anime.path("my_list_status").path("num_episodes_watched").asInt(0)
        if (episode <= watched) return fresh
        val total = anime.path("num_episodes").asInt(0).takeIf { it > 0 } ?: event.totalEpisodes
        val status = if (total != null && episode >= total) "completed" else "watching"
        Http.expect(
            "PATCH", "$apiBase/anime/$malId/my_list_status", auth(current),
            Http.formBody(mapOf("status" to status, "num_watched_episodes" to episode.toString())),
        )
        return fresh
    }

    override suspend fun list(account: TrackerAccount): List<TrackedTitle> {
        val current = refreshed(account)?.also { Trackers.save(this, it) } ?: account
        return listOf("watching", "plan_to_watch").flatMap { status ->
            Http.expect("GET", "$apiBase/users/@me/animelist?status=$status&fields=list_status,num_episodes,start_season&limit=100", auth(current))
                .path("data").map { item ->
                    val node = item.path("node")
                    val total = node.path("num_episodes").asInt(0)
                    val detail = if (status == "plan_to_watch") "À voir" else
                        "Épisode ${item.path("list_status").path("num_episodes_watched").asInt()}" + if (total > 0) " / $total" else ""
                    TrackedTitle(
                        node.path("title").asText(), node.path("main_picture").path("large").asText().ifBlank { null },
                        detail, node.path("start_season").path("year").asInt(0).takeIf { it > 0 },
                    )
                }
        }
    }

    override suspend fun setStatus(account: TrackerAccount, event: WatchEvent, status: com.cloudstreampc.core.WatchStatus): TrackerAccount? {
        val fresh = refreshed(account)
        val malId = event.malId ?: error("identifiant MyAnimeList inconnu")
        val value = when (status) {
            com.cloudstreampc.core.WatchStatus.WATCHING -> "watching"
            com.cloudstreampc.core.WatchStatus.PLAN_TO_WATCH -> "plan_to_watch"
            com.cloudstreampc.core.WatchStatus.ON_HOLD -> "on_hold"
            com.cloudstreampc.core.WatchStatus.DROPPED -> "dropped"
            com.cloudstreampc.core.WatchStatus.COMPLETED -> "completed"
        }
        Http.expect("PATCH", "$apiBase/anime/$malId/my_list_status", auth(fresh ?: account), Http.formBody(mapOf("status" to value)))
        return fresh
    }
}
