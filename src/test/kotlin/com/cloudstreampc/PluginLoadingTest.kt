package com.cloudstreampc

import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.plugins.PluginState
import com.cloudstreampc.plugins.RepositoryManager
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Network test: installs the extensions of a real repository and checks they register providers.
 * Run with `./gradlew test -Dtest.network=true` (add `-Dtest.search=true` to also search and fetch links).
 */
class PluginLoadingTest {
    private val repo = System.getProperty("test.repo")
        ?: "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json"

    @Test
    fun installsAndLoadsRepositoryExtensions() = runBlocking {
        if (System.getProperty("test.network") != "true") return@runBlocking
        val plugins = RepositoryManager.fetchPlugins(repo)
        println("Extensions disponibles : ${plugins.map { it.internalName }}")
        assertTrue(plugins.isNotEmpty())
        for (plugin in plugins) PluginManager.install(plugin, repo)
        println("États : ${PluginManager.states.toMap()}")
        println("Fournisseurs : ${APIHolder.allProviders.map { "${it.name} (${it.mainUrl})" }}")
        assertTrue(PluginManager.states.values.all { it is PluginState.Loaded })
        assertTrue(APIHolder.allProviders.isNotEmpty())

        if (System.getProperty("test.search") == "true") {
            for (api in APIHolder.allProviders.toList()) {
                val result = runCatching { api.search("batman", 1)?.items?.take(3)?.map { it.name } }
                println("Recherche ${api.name} : $result")
                val first = runCatching { api.search("batman", 1)?.items?.firstOrNull() }.getOrNull() ?: continue
                val load = runCatching { api.load(first.url) }.onFailure { println("load ${api.name} : $it") }.getOrNull() ?: continue
                val data = when (load) {
                    is com.lagradost.cloudstream3.MovieLoadResponse -> load.dataUrl
                    is com.lagradost.cloudstream3.TvSeriesLoadResponse -> load.episodes.firstOrNull()?.data
                    is com.lagradost.cloudstream3.AnimeLoadResponse -> load.episodes.values.flatten().firstOrNull()?.data
                    else -> null
                } ?: continue
                val links = mutableListOf<String>()
                runCatching {
                    api.loadLinks(data, false, {}) { links += "${it.name} ${it.quality} ${it.type} ${it.url.take(80)}" }
                }.onFailure { println("loadLinks ${api.name} : $it") }
                println("Liens ${api.name} (${load.name}) : ${links.size}\n  " + links.take(5).joinToString("\n  "))
            }
        }
    }
}
