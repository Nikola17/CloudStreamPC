package com.moncloudi

import com.moncloudi.plugins.PluginManager
import com.moncloudi.plugins.RepositoryManager
import com.moncloudi.providers.TopAvailability
import com.moncloudi.providers.TopRecentRanking
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Network: the real Top 10 (Cinemeta + Wikidata titles), kept to what real French extensions have. */
class TopAvailabilityLiveTest {
    @Test
    fun top10OnlyHasTitlesTheExtensionsHave(): Unit = runBlocking {
        if (System.getProperty("test.network") != "true") return@runBlocking
        val repo = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json"
        RepositoryManager.fetchPlugins(repo).filter { it.name in setOf("FrenchStreamProvider", "Movix") }.forEach { PluginManager.install(it, repo) }
        val providers = APIHolder.allProviders.toList()
        println("Addons : " + providers.map { it.name })
        for (type in listOf("movie", "series")) {
            val candidates = TopRecentRanking.candidates(type)
            println("$type : ${candidates.size} candidats, 10 premiers : " + candidates.take(10).map { "${it.title}${it.originalTitle?.let { o -> " / $o" } ?: ""} (${it.releasedAt ?: it.year})" })
            val entries = TopAvailability.available(candidates, providers)
            entries.forEachIndexed { i, e -> println("  #${i + 1} ${e.item.title} (${e.item.releasedAt ?: e.item.year}) → " + e.matches.map { "${it.apiName}: ${it.name}" }) }
            assertTrue(entries.all { it.matches.isNotEmpty() })
        }
    }
}
