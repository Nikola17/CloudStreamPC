package com.cloudstreampc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudstreampc.core.Store
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Survives tab switches so the home page is not reloaded every time. */
object HomeState {
    var loadedFor by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val sections = mutableStateMapOf<Int, List<HomePageList>>()
    val failedSections = mutableStateListOf<String>()

    suspend fun load(api: MainAPI) = coroutineScope {
        loadedFor = api.name
        loading = true
        error = null
        sections.clear()
        failedSections.clear()
        val permits = Semaphore(4)
        api.mainPage.mapIndexed { index, page ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    try {
                        val response = api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                        val lists = response?.items.orEmpty().filter { it.list.isNotEmpty() }
                        withContext(Dispatchers.Main) { sections[index] = lists }
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) { failedSections += page.name.ifBlank { api.name } }
                    }
                }
            }
        }.awaitAll()
        loading = false
        if (sections.values.all { it.isEmpty() }) {
            error = if (failedSections.isNotEmpty()) "Le site ne répond pas ou a changé." else "Aucun contenu."
        }
    }
}

@Composable
fun HomeScreen() {
    val nav = LocalNavigator.current
    val providers = PluginManager.providers.filter { it.hasMainPage }
    val scope = rememberCoroutineScope()

    if (providers.isEmpty()) {
        MessageBox(
            "Aucune extension avec page d'accueil",
            "Ajoutez un dépôt et installez des extensions pour voir du contenu ici.",
        ) { Button(onClick = { nav.select(Tab.Extensions) }) { Text("Gérer les extensions") } }
        return
    }

    val selected = providers.firstOrNull { it.name == Store.settings.homeProvider } ?: providers.first()
    LaunchedEffect(selected.name) {
        if (HomeState.loadedFor != selected.name) HomeState.load(selected)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(24.dp, 20.dp, 24.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderPicker(providers, selected) { api ->
                Store.updateSettings { it.copy(homeProvider = api.name) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { HomeState.load(selected) } }) {
                Icon(Icons.Default.Refresh, "Recharger")
            }
        }

        val ordered = HomeState.sections.toSortedMap().flatMap { (index, lists) -> lists.map { index to it } }
        when {
            ordered.isEmpty() && HomeState.loading -> LoadingBox("Chargement de ${selected.name}…")
            ordered.isEmpty() && HomeState.error != null -> MessageBox(
                "Impossible de charger ${selected.name}", HomeState.error,
            ) { FilledTonalButton(onClick = { scope.launch { HomeState.load(selected) } }) { Text("Réessayer") } }

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(ordered, key = { (index, section) -> "$index/${section.name}/${section.list.first().url}" }) { (index, section) ->
                    val page = selected.mainPage.getOrNull(index)
                    PosterRow(
                        section.name,
                        section.list,
                        horizontal = section.isHorizontalImages,
                        onSeeAll = page?.let { { nav.push(Route.Category(selected.name, it)) } },
                        onOpen = nav::open,
                    )
                }
                if (HomeState.loading) item { LoadingBox(modifier = Modifier.fillMaxWidth().height(120.dp)) }
            }
        }
    }
}

@Composable
fun ProviderPicker(providers: List<MainAPI>, selected: MainAPI, onSelect: (MainAPI) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }) {
            Text(selected.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            providers.forEach { api ->
                DropdownMenuItem(
                    text = { Text(api.name + (api.lang.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "")) },
                    onClick = {
                        open = false
                        onSelect(api)
                    },
                )
            }
        }
    }
}

/** "Voir tout" for one home page section, paginated. */
@Composable
fun CategoryScreen(route: Route.Category) {
    val nav = LocalNavigator.current
    val api = PluginManager.providers.firstOrNull { it.name == route.apiName }
    val items = remember(route) { mutableStateListOf<SearchResponse>() }
    var page by remember(route) { mutableStateOf(0) }
    var hasNext by remember(route) { mutableStateOf(true) }
    var loading by remember(route) { mutableStateOf(false) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    suspend fun loadMore() {
        if (api == null || loading || !hasNext) return
        loading = true
        try {
            val next = page + 1
            val response = withContext(Dispatchers.IO) {
                api.getMainPage(next, MainPageRequest(route.page.name, route.page.data, route.page.horizontalImages))
            }
            val newItems = response?.items.orEmpty().flatMap { it.list }
                .filter { candidate -> items.none { it.url == candidate.url } }
            items += newItems
            page = next
            hasNext = (response?.hasNext ?: false) && newItems.isNotEmpty()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            hasNext = false
        } finally {
            loading = false
        }
    }

    LaunchedEffect(route) { loadMore() }
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= items.size - 6) loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("${route.page.name} · ${route.apiName}") { nav.back() }
        if (items.isEmpty() && loading) {
            LoadingBox()
        } else if (items.isEmpty()) {
            MessageBox("Rien à afficher", error)
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(if (route.page.horizontalImages) 240.dp else 140.dp),
                state = grid,
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.url }) { SearchResponseCard(it, route.page.horizontalImages, nav::open) }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadingBox(modifier = Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

