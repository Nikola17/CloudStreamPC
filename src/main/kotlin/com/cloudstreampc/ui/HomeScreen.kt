package com.cloudstreampc.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.HistoryEntry
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Providers shown in the app, after the language / NSFW / media filters of "Fournisseurs". */
fun visibleProviders(): List<MainAPI> {
    val settings = Store.settings
    return PluginManager.providers.filter { api ->
        (settings.providerLanguages.isEmpty() || api.lang in settings.providerLanguages || api.sourcePlugin == "builtin") &&
            (settings.nsfw || TvType.NSFW !in api.supportedTypes) &&
            (settings.preferredMedia.isEmpty() || api.supportedTypes.any { it.name in settings.preferredMedia })
    }
}

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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
    val providers = visibleProviders().filter { it.hasMainPage }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    if (providers.isEmpty()) {
        MessageBox(
            "Aucune extension avec page d'accueil",
            "Ajoutez un dépôt d'extensions, un compte IPTV ou un addon Stremio dans les paramètres.",
        ) { Button(onClick = { nav.select(Tab.Settings); nav.push(Route.Settings(SettingsPage.Extensions)) }) { Text("Gérer les extensions") } }
        return
    }

    val selected = providers.firstOrNull { it.name == Store.settings.homeProvider } ?: providers.first()
    LaunchedEffect(selected.name) {
        if (HomeState.loadedFor != selected.name) HomeState.load(selected)
    }

    // Unfinished titles, plus series whose last launched episode was finished (next one to watch).
    val continueWatching = Store.library.history.filter { entry ->
        val data = entry.episodeData ?: return@filter false
        entry.item.type != "Live" && (!Store.isWatched(entry.item.apiName, data) || entry.episodeLabel != null)
    }.take(20)
    val ordered = HomeState.sections.toSortedMap().flatMap { (index, lists) -> lists.map { index to it } }

    Box(Modifier.fillMaxSize()) {
        when {
            ordered.isEmpty() && HomeState.loading -> Column {
                ContinueWatchingRow(continueWatching)
                RecommendationRows()
                LoadingBox("Chargement de ${selected.name}…")
            }
            ordered.isEmpty() && HomeState.error != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                ContinueWatchingRow(continueWatching)
                RecommendationRows()
                MessageBox("Impossible de charger ${selected.name}", HomeState.error, Modifier.fillMaxWidth().height(260.dp)) {
                    FilledTonalButton(onClick = { scope.launch { HomeState.load(selected) } }) { Text("Réessayer") }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (Store.settings.showHero) {
                    val featured = ordered.firstOrNull { !it.second.isHorizontalImages }?.second?.list?.take(6).orEmpty()
                    if (featured.isNotEmpty()) item(key = "hero") { HeroBanner(selected, featured) }
                } else {
                    item(key = "top") { Spacer(Modifier.height(16.dp)) }
                }
                item(key = "continue") { ContinueWatchingRow(continueWatching) }
                item(key = "recommendations") { RecommendationRows() }
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

        if (Store.settings.showClock) Clock(Modifier.align(Alignment.TopEnd).padding(20.dp))

        // Floating buttons, bottom right: random title and provider filter (like the app).
        Column(
            Modifier.align(Alignment.BottomEnd).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (Store.settings.randomButton) {
                RoundButton(AppIcons.Shuffle, "Titre au hasard", small = true) {
                    ordered.flatMap { it.second.list }.randomOrNull()?.let(nav::open)
                }
            }
            RoundButton(Icons.Default.Refresh, "Recharger", small = true) { scope.launch { HomeState.load(selected) } }
            RoundButton(AppIcons.FilterList, "Choisir le fournisseur") { picking = true }
        }
    }

    if (picking) {
        ProviderPickerDialog(providers, selected, onDismiss = { picking = false }) { api ->
            picking = false
            Store.updateSettings { it.copy(homeProvider = api.name) }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, small: Boolean = false, onClick: () -> Unit) {
    val size = if (small) 48.dp else 64.dp
    Box(
        Modifier
            .size(size)
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(if (small) 14.dp else 18.dp))
            .background(CsColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(if (small) 22.dp else 30.dp))
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = LocalTime.now()
        }
    }
    Text(
        now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(CsColors.Badge).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Big featured banner at the top of the home page, rotating between a few titles. */
@Composable
private fun HeroBanner(api: MainAPI, items: List<SearchResponse>) {
    val nav = LocalNavigator.current
    var index by remember(items) { mutableIntStateOf(0) }
    val item = items[index % items.size]
    var details by remember(item.url) { mutableStateOf(DetailsCache.cached(api.name, item.url)) }
    LaunchedEffect(item.url) {
        if (details == null) details = runCatching { DetailsCache.load(api, item.url) }.getOrNull()
    }
    LaunchedEffect(items) {
        while (true) {
            delay(10_000)
            index = (index + 1) % items.size
        }
    }
    val response: LoadResponse? = details
    Box(Modifier.fillMaxWidth().height(480.dp)) {
        Crossfade(response?.backgroundPosterUrl ?: response?.posterUrl ?: item.posterUrl) { image ->
            RemoteImage(image, Modifier.fillMaxSize(), item.posterHeaders, placeholder = { Box(Modifier.fillMaxSize().background(CsColors.Surface)) })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(CsColors.Background, CsColors.Background.copy(alpha = 0.6f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to CsColors.Background)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 36.dp).widthIn(max = 640.dp)) {
            Text(
                item.name, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 48.sp,
            )
            val meta = listOfNotNull(
                (response?.year ?: item.yearOrNull())?.toString(),
                response?.duration?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                response?.tags?.take(3)?.joinToString(", "),
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                (response?.score ?: item.score)?.let {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
                    Text(" %.1f    ".format(it.toDouble(10)), fontWeight = FontWeight.SemiBold)
                }
                Text(meta.joinToString("  ·  "), color = Color(0xFFDDDDDD))
            }
            response?.plot?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xFFDDDDDD), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp), lineHeight = 21.sp)
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { response?.let { PlayQueue.play(api, it) } ?: nav.open(item) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(" Lire", fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = { nav.open(item) }) {
                    Icon(Icons.Default.Info, null)
                    Text(" Plus d'infos")
                }
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier.size(width = if (i == index % items.size) 22.dp else 8.dp, height = 8.dp)
                            .clip(CircleShape)
                            .background(if (i == index % items.size) Color.White else Color.White.copy(alpha = 0.35f))
                            .clickable { index = i }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(entries: List<HistoryEntry>) {
    if (entries.isEmpty()) return
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp, top = 8.dp)) {
        Text(
            "Continuer à regarder",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 18.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.item.apiName + it.item.url }) { entry ->
                val progress = entry.episodeData?.let { Store.progressOf(entry.item.apiName, it) }
                PosterCard(
                    entry.item.name,
                    entry.item.posterUrl,
                    subtitle = when {
                        entry.episodeData != null && Store.isWatched(entry.item.apiName, entry.episodeData) -> "Épisode suivant"
                        else -> listOfNotNull(
                            entry.episodeLabel?.substringBefore("  "),
                            progress?.let { "${formatTime(it.position)} / ${formatTime(it.duration)}" },
                        ).joinToString(" · ").ifBlank { entry.item.apiName }
                    },
                    progress = progress?.fraction,
                ) { nav.open(entry.item) }
            }
        }
    }
}

private val typeFilters = listOf(
    "Films" to setOf(TvType.Movie, TvType.AnimeMovie),
    "Séries" to setOf(TvType.TvSeries),
    "Animés" to setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
    "Dessins animés" to setOf(TvType.Cartoon),
    "Documentaires" to setOf(TvType.Documentary),
    "Drama asiatiques" to setOf(TvType.AsianDrama),
    "TV en direct" to setOf(TvType.Live),
)

/** The app's provider selector (filter button): type chips + provider list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderPickerDialog(providers: List<MainAPI>, selected: MainAPI, onDismiss: () -> Unit, onSelect: (MainAPI) -> Unit) {
    var filter by remember { mutableStateOf<String?>(null) }
    val types = typeFilters.firstOrNull { it.first == filter }?.second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fournisseur", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(520.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, { Text("Tous") })
                    typeFilters.filter { (_, set) -> providers.any { p -> p.supportedTypes.any { it in set } } }.forEach { (label, _) ->
                        FilterChip(filter == label, { filter = label }, { Text(label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(providers.filter { p -> types == null || p.supportedTypes.any { it in types } }) { api ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (api == selected) CsColors.Primary.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { onSelect(api) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(api.name, fontSize = 16.sp, fontWeight = if (api == selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            Text(api.lang.uppercase(), color = CsColors.TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
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

