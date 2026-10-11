package com.cloudstreampc.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cloudstreampc.core.SavedItem
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    Home("Accueil"),
    Search("Recherche"),
    Library("Bibliothèque"),
    Downloads("Téléchargements"),
    Settings("Paramètres"),
}

/** Sub-pages of the settings, like the Android app. */
enum class SettingsPage(val title: String) {
    General("Général"),
    Player("Lecteur"),
    Providers("Fournisseurs"),
    Layout("Disposition"),
    Updates("Mises à jour et Sauvegarde"),
    Accounts("Comptes et Sécurité"),
    Extensions("Extensions"),
    Iptv("Comptes IPTV"),
    Profiles("Profils"),
}

sealed interface Route {
    data class Details(val apiName: String, val url: String, val name: String, val posterUrl: String?) : Route
    data class Category(val apiName: String, val page: MainPageData) : Route
    data class Settings(val page: SettingsPage) : Route
    data object TvGuide : Route
}

class Navigator(private val scope: CoroutineScope) {
    var tab by mutableStateOf(Tab.Home)
        private set
    val stack = mutableStateListOf<Route>()
    val snackbar = SnackbarHostState()

    fun select(tab: Tab) {
        this.tab = tab
        stack.clear()
    }

    fun push(route: Route) {
        stack += route
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun open(item: SearchResponse) = push(Route.Details(item.apiName, item.url, item.name, item.posterUrl))

    fun open(item: SavedItem) = push(Route.Details(item.apiName, item.url, item.name, item.posterUrl))

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator missing") }
