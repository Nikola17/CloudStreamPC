package com.cloudstreampc.plugins

import com.cloudstreampc.core.mapper
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

    /** Accepts direct URLs as well as `cloudstreamrepo://` links, like the Android app. */
    fun normalizeRepoUrl(input: String): String {
        val url = input.trim()
        if (url.contains(repoPrefix)) {
            val stripped = url.replace(repoPrefix, "")
            return if (stripped.startsWith("http")) stripped else "https://$stripped"
        }
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
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
