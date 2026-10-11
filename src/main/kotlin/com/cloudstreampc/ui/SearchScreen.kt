package com.cloudstreampc.ui

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
import androidx.compose.material3.Text
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
import com.cloudstreampc.plugins.PluginManager
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
    val providers = PluginManager.providers

    fun runSearch() {
        val query = SearchState.query.trim()
        if (query.isEmpty()) return
        SearchState.job?.cancel()
        SearchState.lastQuery = query
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

    LaunchedEffect(Unit) { focus.requestFocus() }

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
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(SearchState.typeFilter == null, { SearchState.typeFilter = null }, { Text("Tout") })
            typeFilters.forEach { (label, _) ->
                FilterChip(SearchState.typeFilter == label, { SearchState.typeFilter = label }, { Text(label) })
            }
        }

        if (providers.isEmpty()) {
            MessageBox("Aucune extension installée", "Installez des extensions pour pouvoir rechercher.")
            return
        }
        if (SearchState.lastQuery.isEmpty()) {
            MessageBox("Recherchez dans toutes vos extensions", "Tapez un titre puis appuyez sur Entrée.")
            return
        }

        val allowedTypes = typeFilters.firstOrNull { it.first == SearchState.typeFilter }?.second
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

