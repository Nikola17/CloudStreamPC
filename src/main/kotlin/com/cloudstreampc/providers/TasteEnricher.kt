package com.cloudstreampc.providers

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.Taste
import com.cloudstreampc.core.Taste.toRecItem
import com.cloudstreampc.plugins.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Many extensions return no "similar titles". For such watched titles, look the same title
 * up in the other providers and borrow their suggestions (all done locally, no account).
 */
object TasteEnricher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private const val RETRY_AFTER_MS = 3 * 86_400_000L

    fun enrichSoon() {
        scope.launch { runCatching { enrich() }.onFailure { Logger.error("Recommandations", it) } }
    }

    suspend fun enrich(maxSeeds: Int = 5) = mutex.withLock {
        val now = System.currentTimeMillis()
        val todo = Taste.current().seeds
            .filter { it.weight > 0 && it.recommendations.isEmpty() && now - it.enrichedAt > RETRY_AFTER_MS }
            .take(maxSeeds)
        for (seed in todo) {
            val title = Recommender.normalizedTitle(seed.name)
            val family = Recommender.family(seed.type)
            var found: Pair<String, List<com.cloudstreampc.core.RecItem>>? = null
            // Providers known to return suggestions first, live TV is useless here.
            val candidates = PluginManager.providers.filter { it.name != seed.apiName && it.hasMainPage }
            for (api in candidates) {
                val match = withTimeoutOrNull(20_000) {
                    runCatching { api.search(seed.name, 1)?.items.orEmpty() }.getOrDefault(emptyList())
                }.orEmpty().firstOrNull {
                    Recommender.normalizedTitle(it.name) == title &&
                        (family == null || Recommender.family(it.type?.name) in setOf(family, null))
                } ?: continue
                val loaded = withTimeoutOrNull(25_000) { runCatching { api.load(match.url) }.getOrNull() } ?: continue
                val recs = loaded.recommendations.orEmpty().map { it.toRecItem() }
                if (recs.isNotEmpty()) {
                    found = api.name to recs
                    break
                }
            }
            Taste.setEnrichment(seed.apiName, seed.url, found?.second.orEmpty(), found?.first)
            Logger.info("Recommandations pour « ${seed.name} » : ${found?.second?.size ?: 0} via ${found?.first ?: "aucun fournisseur"}")
        }
    }
}
