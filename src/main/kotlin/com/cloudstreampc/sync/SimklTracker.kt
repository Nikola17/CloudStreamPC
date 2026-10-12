package com.cloudstreampc.sync

import com.cloudstreampc.core.TrackerAccount
import kotlinx.coroutines.delay

/** Simkl: login with a code typed on simkl.com (PIN flow), history through the sync API. */
object SimklTracker : Tracker {
    override val id = "simkl"
    override val name = "Simkl"
    override val developerUrl = "https://simkl.com/settings/developer/new/"
    override val setupSteps = listOf(
        "Sur Simkl, ouvrez Settings → Developer → « Create new app ».",
        "Nom : CloudStreamPC. Redirect URI : ${OAuthLoopback.REDIRECT_URL} (non utilisée, mais obligatoire).",
        "Enregistrez, puis copiez le « Client ID » ci-dessous.",
    )
    override val needsSecret = false
    override val animeOnly = false

    internal var apiBase = "https://api.simkl.com"
    /** Tests poll faster than the interval Simkl asks for. */
    internal var pollDelayOverride: Long? = null

    private fun headers(account: TrackerAccount) = buildMap {
        put("simkl-api-key", account.clientId.trim())
        put("Content-Type", "application/json")
        account.accessToken?.let { put("Authorization", "Bearer $it") }
    }

    override suspend fun login(account: TrackerAccount, ui: LoginUi): TrackerAccount {
        val clientId = account.clientId.trim()
        require(clientId.isNotEmpty()) { "Indiquez le Client ID de votre application Simkl." }
        val start = Http.expect("GET", "$apiBase/oauth/pin?client_id=$clientId", headers(account))
        val code = start.path("user_code").asText().ifBlank { error("Simkl n'a pas donné de code.") }
        val url = start.path("verification_url").asText().ifBlank { "https://simkl.com/pin/" }
        ui.showCode(code, url)
        val deadline = System.currentTimeMillis() + start.path("expires_in").asLong(900) * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(pollDelayOverride ?: (start.path("interval").asLong(5) * 1000))
            val answer = Http.call("GET", "$apiBase/oauth/pin/$code?client_id=$clientId", headers(account))
            val token = answer.body.path("access_token").asText()
            if (answer.body.path("result").asText() == "OK" && token.isNotBlank()) {
                val logged = account.copy(clientId = clientId, accessToken = token)
                val settings = Http.call("POST", "$apiBase/users/settings", headers(logged), Http.jsonBody(emptyMap<String, Any>()))
                return logged.copy(userName = settings.body.path("user").path("name").asText().ifBlank { null })
            }
        }
        error("Le code a expiré : recommencez.")
    }

    override suspend fun markWatched(account: TrackerAccount, event: WatchEvent): TrackerAccount? {
        val ids = buildMap<String, Any> {
            event.imdbId?.let { put("imdb", it) }
            if (event.isAnime) event.malId?.let { put("mal", it) }
        }
        val item = buildMap<String, Any> {
            put("title", event.title)
            event.year?.let { put("year", it) }
            if (ids.isNotEmpty()) put("ids", ids)
            if (!event.isMovie && event.episode != null) {
                put("seasons", listOf(mapOf("number" to (event.season ?: 1), "episodes" to listOf(mapOf("number" to event.episode)))))
            }
        }
        val body = if (event.isMovie) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
        Http.expect("POST", "$apiBase/sync/history", headers(account), Http.jsonBody(body))
        return null
    }

    override suspend fun list(account: TrackerAccount): List<TrackedTitle> =
        listOf("shows" to "show", "anime" to "show", "movies" to "movie").flatMap { (kind, key) ->
            listOf("watching", "plantowatch").flatMap { status ->
                val answer = Http.call("GET", "$apiBase/sync/all-items/$kind/$status", headers(account))
                if (!answer.ok) return@flatMap emptyList()
                answer.body.path(kind).map { entry ->
                    val media = entry.path(key)
                    val poster = media.path("poster").asText().ifBlank { null }?.let { "https://simkl.in/posters/${it}_m.jpg" }
                    val watched = entry.path("watched_episodes_count").asInt(0)
                    val total = entry.path("total_episodes_count").asInt(0)
                    val detail = when {
                        status == "plantowatch" -> "À voir"
                        key == "movie" -> "En cours"
                        else -> "Épisode $watched" + if (total > 0) " / $total" else ""
                    }
                    TrackedTitle(media.path("title").asText(), poster, detail, media.path("year").asInt(0).takeIf { it > 0 })
                }
            }
        }

    override suspend fun setStatus(account: TrackerAccount, event: WatchEvent, status: com.cloudstreampc.core.WatchStatus): TrackerAccount? {
        val ids = buildMap<String, Any> {
            event.imdbId?.let { put("imdb", it) }
            if (event.isAnime) event.malId?.let { put("mal", it) }
        }
        val item = buildMap<String, Any> {
            put("title", event.title)
            event.year?.let { put("year", it) }
            if (ids.isNotEmpty()) put("ids", ids)
            put(
                "to",
                when (status) {
                    com.cloudstreampc.core.WatchStatus.WATCHING -> "watching"
                    com.cloudstreampc.core.WatchStatus.PLAN_TO_WATCH -> "plantowatch"
                    com.cloudstreampc.core.WatchStatus.ON_HOLD -> "hold"
                    com.cloudstreampc.core.WatchStatus.DROPPED -> "dropped"
                    com.cloudstreampc.core.WatchStatus.COMPLETED -> "completed"
                },
            )
        }
        val body = if (event.isMovie) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
        Http.expect("POST", "$apiBase/sync/add-to-list", headers(account), Http.jsonBody(body))
        return null
    }
}
