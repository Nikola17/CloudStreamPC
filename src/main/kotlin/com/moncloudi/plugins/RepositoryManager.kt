package com.moncloudi.plugins

import com.moncloudi.core.mapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app

/** Same format as the Android app's repo.json. */
data class Repository(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("manifestVersion") val manifestVersion: Int = 1,
    @JsonProperty("pluginLists") val pluginLists: List<String> = emptyList(),
)

/** Same format as the Android app's plugins.json entries. */
data class SitePlugin(
    @JsonProperty("url") val url: String = "",
    @JsonProperty("status") val status: Int = 1,
    @JsonProperty("version") val version: Int = 0,
    @JsonProperty("apiVersion") val apiVersion: Int = 1,
    @JsonProperty("name") val name: String = "",
    @JsonProperty("internalName") val internalName: String = "",
    @JsonProperty("authors") val authors: List<String> = emptyList(),
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("repositoryUrl") val repositoryUrl: String? = null,
    @JsonProperty("tvTypes") val tvTypes: List<String>? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("fileSize") val fileSize: Long? = null,
)

object RepositoryManager {
    private val repoPrefix = "^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex()
    private val shortCodePattern = "^[a-zA-Z0-9!_-]+$".toRegex()

    /** Link shorteners of the repositories' short codes, as in the Android app (tests point them elsewhere). */
    internal var cuttlyBase = "https://cutt.ly"
    internal var pymdBase = "https://py.md"

    /** Accepts direct URLs as well as `cloudstreamrepo://` links, like the Android app. */
    fun normalizeRepoUrl(input: String): String {
        val url = input.trim()
        if (url.contains(repoPrefix)) {
            val stripped = url.replace(repoPrefix, "")
            return if (stripped.startsWith("http")) stripped else "https://$stripped"
        }
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }

    /** A short code ("cspr", "!abc") rather than an address: letters, digits, `!`, `_` and `-` only. */
    fun isShortCode(input: String) = input.trim().matches(shortCodePattern)

    /**
     * What the user typed in "Ajouter un dépôt", like CloudStream's `parseRepoUrl`: an address, a
     * `cloudstreamrepo://` link, or a short code. A code is the end of a cutt.ly link (or of a py.md
     * one when it starts with `!`) that redirects to the repository: the redirection is read
     * without following it. Null when the code leads nowhere.
     */
    suspend fun parseRepoUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (!isShortCode(text)) return normalizeRepoUrl(text)
        val (base, code) = if (text.startsWith("!")) pymdBase to text.removePrefix("!") else cuttlyBase to text
        if (code.isEmpty()) return null
        val location = runCatching { app.get("$base/$code", allowRedirects = false, cacheTime = 0).headers["Location"] }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (location.startsWith("$base/404") || location.removeSuffix("/") == base) return null
        return normalizeRepoUrl(location)
    }

    suspend fun fetchRepository(url: String): Repository {
        val text = app.get(normalizeRepoUrl(url), cacheTime = 0).text
        return mapper.readValue(text)
    }

    suspend fun fetchPlugins(repo: Repository): List<SitePlugin> =
        repo.pluginLists.flatMap { listUrl ->
            runCatching { mapper.readValue<List<SitePlugin>>(app.get(listUrl, cacheTime = 0).text) }
                .getOrDefault(emptyList())
        }

    suspend fun fetchPlugins(repoUrl: String): List<SitePlugin> = fetchPlugins(fetchRepository(repoUrl))
}
