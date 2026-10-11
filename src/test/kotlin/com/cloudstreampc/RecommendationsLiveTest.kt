package com.cloudstreampc

import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.Taste
import com.cloudstreampc.core.writeJsonFile
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.plugins.RepositoryManager
import com.cloudstreampc.providers.TasteEnricher
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** Network: watches real titles, enriches them and prints the resulting recommendations. */
class RecommendationsLiveTest {
    @Test
    fun recommendationsFromRealExtensions(): Unit = runBlocking {
        if (System.getProperty("test.network") != "true") return@runBlocking
        val repo = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json"
        RepositoryManager.fetchPlugins(repo).forEach { PluginManager.install(it, repo) }
        Taste.reset()
        for ((providerName, query) in listOf("French-Stream" to "batman", "Movix" to "inception", "French-Stream" to "breaking bad", "French-Manga" to "one piece")) {
            val api = APIHolder.allProviders.first { it.name == providerName }
            val hit = runCatching { api.search(query, 1)?.items?.firstOrNull() }.getOrNull() ?: continue
            val loaded = runCatching { api.load(hit.url) }.getOrNull() ?: continue
            println("$providerName « ${loaded.name} » : ${loaded.recommendations.orEmpty().size} suggestions de l'extension")
            Taste.record(loaded, 2.0)
        }
        TasteEnricher.enrich(10)
        Taste.current().seeds.forEach { println("Graine « ${it.name} » : ${it.recommendations.size} suggestions (${it.enrichedFrom ?: it.apiName})") }
        val recs = Recommender.recommend(Taste.current(), emptySet())
        println("Recommandé pour vous : " + recs.take(12).map { "${it.item.name} [${it.item.apiName}] (${it.because.joinToString()})" })
        println("Rangées : " + Recommender.becauseRows(Taste.current(), emptySet()).map { "${it.seed.name} → ${it.items.size}" })
        System.getProperty("test.tasteOut")?.let { writeJsonFile(File(it), Taste.current()) }
    }
}
