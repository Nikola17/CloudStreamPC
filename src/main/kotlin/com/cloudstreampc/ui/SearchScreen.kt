package com.cloudstreampc.ui

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Store
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProviderResult {
    data object Loading : ProviderResult
    data class Done(val items: List<SearchResponse>) : ProviderResult
    data class Failed(val message: String) : ProviderResult
}

object SearchState {
    var query by mutableStateOf("")
    var lastQuery by mutableStateOf("")
    var typeFilter by mutableStateOf<String?>(null)
    val results = mutableStateMapOf<String, ProviderResult>()
    var job: Job? = null
    /** Set by other screens to search [query] as soon as the tab opens. */
    var pendingSearch by mutableStateOf(false)
    /** One card per title (all providers together) instead of one row per provider. */
    var merged by mutableStateOf(true)
}

private fun yearOf(item: SearchResponse): Int? = when (item) {
    is com.lagradost.cloudstream3.MovieSearchResponse -> item.year
    is com.lagradost.cloudstream3.TvSeriesSearchResponse -> item.year
    is com.lagradost.cloudstream3.AnimeSearchResponse -> item.year
    else -> null
}

/**
 * The same title from several providers becomes one entry (same name, years within one year of
 * each other); titles found by more providers come first.
 */
internal fun mergeResults(results: List<SearchResponse>): List<List<SearchResponse>> {
    val groups = linkedMapOf<String, MutableList<MutableList<SearchResponse>>>()
    for (item in results) {
        val buckets = groups.getOrPut(com.cloudstreampc.core.Recommender.normalizedTitle(item.name)) { mutableListOf() }
        val year = yearOf(item)
        val bucket = buckets.firstOrNull { b ->
            val other = b.firstNotNullOfOrNull(::yearOf)
            year == null || other == null || kotlin.math.abs(other - year) <= 1
        }
        if (bucket != null) {
            if (bucket.none { it.apiName == item.apiName }) bucket += item
        } else {
            buckets += mutableListOf(item)
        }
    }
    return groups.values.flatten().withIndex()
        .sortedWith(compareByDescending<IndexedValue<MutableList<SearchResponse>>> { it.value.size }.thenBy { it.index })
        .map { it.value }
}

private val typeFilters = listOf(
    "Films" to setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary),
    "Séries" to setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon),
    "Animés" to setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
    "Direct" to setOf(TvType.Live),
)

@Composable
fun SearchScreen() {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val providers = visibleProviders()

    fun runSearch() {
        val query = SearchState.query.trim()
        if (query.isEmpty()) return
        SearchState.job?.cancel()
        SearchState.lastQuery = query
        Store.addSearchQuery(query)
        SearchState.results.clear()
        providers.forEach { SearchState.results[it.name] = ProviderResult.Loading }
        SearchState.job = scope.launch {
            coroutineScope {
                providers.map { api ->
                    async {
                        val result = try {
                            val items = withContext(Dispatchers.IO) { api.search(query, 1)?.items.orEmpty() }
                            ProviderResult.Done(items)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            ProviderResult.Failed(t.message ?: t.javaClass.simpleName)
                        }
                        SearchState.results[api.name] = result
                    }
                }.awaitAll()
            }
        }
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        if (SearchState.pendingSearch) {
            SearchState.pendingSearch = false
            runSearch()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(24.dp, 20.dp, 24.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = SearchState.query,
                onValueChange = { SearchState.query = it },
                placeholder = { Text("Rechercher un film, une série, un animé…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (SearchState.query.isNotEmpty()) {
                        IconButton(onClick = { SearchState.query = "" }) { Icon(Icons.Default.Close, "Effacer") }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent {
                        if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                            runSearch()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(SearchState.typeFilter == null, { SearchState.typeFilter = null }, { Text("Tout") })
            typeFilters.forEach { (label, _) ->
                FilterChip(SearchState.typeFilter == label, { SearchState.typeFilter = label }, { Text(label) })
            }
            Spacer(Modifier.width(24.dp))
            FilterChip(SearchState.merged, { SearchState.merged = true }, { Text("Par titre") })
            FilterChip(!SearchState.merged, { SearchState.merged = false }, { Text("Par fournisseur") })
        }

        if (providers.isEmpty()) {
            MessageBox("Aucune extension installée", "Installez des extensions pour pouvoir rechercher.")
            return
        }
        if (SearchState.lastQuery.isEmpty()) {
            val history = Store.library.searchHistory
            if (history.isEmpty() || !Store.settings.searchHistoryEnabled) {
                MessageBox("Recherchez dans toutes vos extensions", "Tapez un titre puis appuyez sur Entrée.")
            } else {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recherches récentes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Store.updateLibrary { it.copy(searchHistory = emptyList()) } }) { Text("Effacer") }
                    }
                    history.forEach { query ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { SearchState.query = query; runSearch() }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Search, null, tint = CsColors.TextMuted)
                            Text("   $query", fontSize = 16.sp)
                        }
                    }
                }
            }
            return
        }

        val allowedTypes = typeFilters.firstOrNull { it.first == SearchState.typeFilter }?.second
        if (SearchState.merged) {
            MergedResults(providers.map { it.name }, allowedTypes)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            val entries = providers.mapNotNull { api -> SearchState.results[api.name]?.let { api.name to it } }
                .sortedBy { (_, result) -> if (result is ProviderResult.Done && result.items.isNotEmpty()) 0 else 1 }
            items(entries, key = { it.first }) { (name, result) ->
                when (result) {
                    ProviderResult.Loading -> ProviderStatusRow(name, "Recherche…", loading = true)
                    is ProviderResult.Failed -> ProviderStatusRow(name, "Erreur : ${result.message}")
                    is ProviderResult.Done -> {
                        val items = result.items.filter { allowedTypes == null || it.type == null || it.type in allowedTypes }
                        if (items.isEmpty()) {
                            ProviderStatusRow(name, "Aucun résultat")
                        } else {
                            PosterRow("$name  (${items.size})", items, onOpen = nav::open)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusRow(name: String, status: String, loading: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(status, color = CsColors.TextMuted, maxLines = 1)
    }
}

/** One card per title; several providers: a small list to pick where to open it. */
@Composable
private fun MergedResults(providerOrder: List<String>, allowedTypes: Set<TvType>?) {
    val nav = LocalNavigator.current
    var choosing by remember { mutableStateOf<List<SearchResponse>?>(null) }
    val results = SearchState.results
    val loading = results.values.count { it == ProviderResult.Loading }
    val all = providerOrder.flatMap { name -> (results[name] as? ProviderResult.Done)?.items.orEmpty() }
        .filter { allowedTypes == null || it.type == null || it.type in allowedTypes }
    val groups = remember(all) { mergeResults(all) }
    Column(Modifier.fillMaxSize()) {
        if (loading > 0) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("   $loading fournisseur(s) cherchent encore…", color = CsColors.TextMuted, fontSize = 13.sp)
            }
        }
        if (groups.isEmpty() && loading == 0) {
            MessageBox("Aucun résultat", "Essayez un autre titre, ou « Par fournisseur » pour voir les erreurs.")
            return@Column
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(posterWidth),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(groups.size, key = { groups[it].first().apiName + groups[it].first().url }) { index ->
                val group = groups[index]
                val first = group.first()
                PosterCard(
                    first.name, group.firstNotNullOfOrNull { it.posterUrl }, headers = first.posterHeaders,
                    badge = if (group.size > 1) "${group.size} sources" else qualityLabel(first.quality),
                    score = first.score?.toDouble(10)?.takeIf { it > 0 }?.let { "%.1f".format(it).removeSuffix(",0").removeSuffix(".0") },
                    subtitle = if (group.size == 1) first.apiName else group.joinToString(", ") { it.apiName },
                ) { if (group.size == 1) nav.open(first) else choosing = group }
            }
        }
    }
    choosing?.let { group ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(group.first().name) },
            text = {
                Column {
                    Text("Disponible sur :", color = CsColors.TextMuted, fontSize = 13.sp)
                    group.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { choosing = null; nav.open(item) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.apiName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            listOfNotNull(qualityLabel(item.quality), yearOf(item)?.toString()).forEach {
                                Text("  $it", color = CsColors.TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Fermer") } },
        )
    }
}
