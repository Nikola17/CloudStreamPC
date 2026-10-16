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
    /** `-Dtest.fstv.page=https://bzh.lat/…` : the stream the event page's player asks for, seen by the app's browser. */
    @Test
    fun eventPagePlayerGivesAStream() = runBlocking {
        val page = System.getProperty("test.fstv.page") ?: return@runBlocking
        System.setProperty("cloudstreampc.browser", System.getProperty("test.browser") ?: "/opt/pw-browsers/chromium")
        val (found, _) = com.lagradost.cloudstream3.network.WebViewResolver(Regex("""\.m3u8"""), timeout = 40_000).resolveUsingWebView(page, referer = System.getProperty("test.fstv.referer"))
        println("FLUX : ${found?.url} referer=${found?.header("referer")} origin=${found?.header("origin")}")
        com.cloudstreampc.net.ChromiumBrowser.shutdown()
    }

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
