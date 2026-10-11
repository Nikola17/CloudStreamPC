package com.cloudstreampc.providers

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Providers built into the app (IPTV accounts, Stremio addons), registered next to the extensions. */
object BuiltinProviders {
    private const val SOURCE = "builtin"
    private val mutex = Mutex()

    /** Re-creates the IPTV and Stremio providers from the current settings. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = APIHolder.allProviders.filter { it.sourcePlugin == SOURCE }
            old.forEach { APIHolder.removePluginMapping(it) }
            APIHolder.allProviders.removeAll(old.toSet())
            PluginManager.notifyProvidersChanged()

            val settings = Store.settings
            settings.iptvAccounts.filter { it.enabled }.forEach { account ->
                val provider = IptvProvider(account)
                runCatching { provider.prepare() }.onFailure { Logger.error("IPTV ${account.name}", it) }
                register(provider)
            }
            StremioProvider.create(settings.stremioAddons)?.let(::register)
        }
    }

    private fun register(provider: com.lagradost.cloudstream3.MainAPI) {
        provider.sourcePlugin = SOURCE
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)
        PluginManager.notifyProvidersChanged()
    }
}
