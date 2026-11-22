package com.moncloudi

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moncloudi.ui.App
import com.moncloudi.ui.HomeState
import com.moncloudi.ui.Navigator
import com.moncloudi.ui.Route
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/** "Tout voir" of a home row opens the whole category. */
@OptIn(ExperimentalTestApi::class)
class SeeAllUiTest {
    private val api = object : MainAPI() {
        override var name = "Catalogue test"
        override var lang = "fr"
        override val hasMainPage = true
        override val supportedTypes = setOf(TvType.Movie)
        override val mainPage = listOf(MainPageData("Films récents", "recent", false))
        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
            newHomePageResponse(
                HomePageList(request.name, (1..12).map { newMovieSearchResponse("Film $page-$it", "https://test/$page/$it", TvType.Movie) }, false),
                hasNext = page < 3,
            )
    }

    @Test
    fun seeAllOpensTheCategory() = runComposeUiTest {
        APIHolder.allProviders.add(api)
        com.moncloudi.core.Store.updateSettings { it.copy(homeProvider = api.name, firstLaunchDone = true, showHero = false) }
        HomeState.loadedFor = null
        val nav = Navigator(CoroutineScope(Dispatchers.Main))
        try {
            setContent { App(nav) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Films récents").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithContentDescription("Tout voir").onFirst().performClick()
            waitForIdle()
            // The page stays open (it used to close itself as soon as it was drawn) and shows the category.
            assertTrue(nav.stack.lastOrNull() is Route.Category, "pile : ${nav.stack.toList()}")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Film 1-1").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(nav.stack.lastOrNull() is Route.Category, "pile : ${nav.stack.toList()}")
            // Its back arrow closes it.
            onAllNodesWithContentDescription("Retour").onFirst().performClick()
            waitForIdle()
            assertTrue(nav.stack.isEmpty(), "pile : ${nav.stack.toList()}")
        } finally {
            APIHolder.allProviders.remove(api)
        }
    }
}
