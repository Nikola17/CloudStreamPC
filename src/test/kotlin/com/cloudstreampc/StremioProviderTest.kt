package com.cloudstreampc

import com.cloudstreampc.core.StremioAddonEntry
import com.cloudstreampc.providers.StremioProvider
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Network test against Cinemeta: `./gradlew test -Dtest.network=true`. */
class StremioProviderTest {
    @Test
    fun cinemetaCatalogsAndMeta() = runBlocking {
        if (System.getProperty("test.network") != "true") return@runBlocking
        val provider = assertNotNull(StremioProvider.create(listOf(StremioAddonEntry(StremioProvider.presets.first().second))))
        println("Catalogues : ${provider.mainPage.map { it.name }}")
        val first = provider.mainPage.first()
        val items = provider.getMainPage(1, MainPageRequest(first.name, first.data, false)).items.first().list
        println("${first.name} : ${items.take(5).map { it.name }}")
        assertTrue(items.isNotEmpty())
        val series = provider.search("breaking bad").first { it.type?.name == "TvSeries" }
        val loaded = provider.load(series.url) as TvSeriesLoadResponse
        println("${loaded.name} : ${loaded.episodes.size} épisodes, ${loaded.plot?.take(60)}")
        assertTrue(loaded.episodes.size > 50)
    }
}
