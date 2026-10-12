package com.cloudstreampc

import com.cloudstreampc.player.LinkResolver
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.plugins.RepositoryManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Network test of French-Stream TV's sports events, the way the app plays them (load, then the
 * sources). Run by hand: `./gradlew test --tests '*FstvEventsTest*' -Dtest.fstv=true`.
 */
class FstvEventsTest {
    @Test
    fun eventsHaveSources() = runBlocking {
        if (System.getProperty("test.fstv") != "true") return@runBlocking
        val repo = "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json"
        RepositoryManager.fetchPlugins(repo).filter { it.internalName == "FSTV" }.forEach { PluginManager.install(it, repo) }
        val api = APIHolder.allProviders.first { it.name == "French-Stream TV" }
        for (page in api.mainPage.take(3)) {
            val events = api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))?.items.orEmpty().flatMap { it.list }
            println("== ${page.name} : ${events.size} événements")
            for (event in events.take(3)) {
                val start = System.currentTimeMillis()
                val response = runCatching { api.load(event.url) }.onFailure { println("  load ${event.name} : $it") }.getOrNull() ?: continue
                val queue = PlayQueue.build(response, emptyList())
                println("  ${event.name} (${event.url}) type=${response.type} file=${queue.size} data=${queue.firstOrNull()?.data?.take(120)}")
                if (queue.isEmpty()) continue
                val resolved = LinkResolver.resolve(PlayRequest(api, response, queue, 0, autoPlay = true))
                println("    ${resolved.links.size} source(s) en ${(System.currentTimeMillis() - start) / 1000.0} s, erreur=${resolved.error}")
                resolved.links.take(4).forEach { println("    - ${it.name} ${it.type} ${it.url.take(100)} referer=${it.referer}") }
            }
        }
    }
}
