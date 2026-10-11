package com.cloudstreampc.ui

import androidx.compose.ui.unit.sp

import androidx.compose.runtime.remember

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cloudstreampc.core.Store
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun LibraryScreen() {
    val nav = LocalNavigator.current
    val library = Store.library
    // 0 favorites, 1 history, 2 new episodes (opened first when there are some, like the sidebar badge says).
    var section by rememberSaveable { mutableStateOf(if (library.newEpisodes.isNotEmpty()) 2 else 0) }
    if (section == 2 && library.newEpisodes.isEmpty()) section = 0
    val showHistory = section == 1

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bibliothèque") {
            if (showHistory && library.history.isNotEmpty()) {
                TextButton(onClick = { Store.clearHistory() }) { Text("Effacer l'historique") }
            }
        }
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (library.newEpisodes.isNotEmpty()) {
                FilterChip(section == 2, { section = 2 }, { Text("Nouveaux épisodes (${library.newEpisodes.size})") })
            }
            FilterChip(section == 0, { section = 0 }, { Text("Favoris (${library.favorites.size})") })
            FilterChip(section == 1, { section = 1 }, { Text("Historique (${library.history.size})") })
            if (com.cloudstreampc.sync.Trackers.connected().isNotEmpty()) {
                FilterChip(section == 3, { section = 3 }, { Text("Listes de suivi") })
            }
        }

        val grid: @Composable (content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) -> Unit = { content ->
            LazyVerticalGrid(
                GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }

        if (section == 3) {
            TrackerLists()
        } else if (section == 2) {
            grid {
                items(library.newEpisodes, key = { "new:" + it.item.apiName + it.item.url }) { release ->
                    PosterCard(
                        release.item.name, release.item.posterUrl,
                        badge = release.label.ifBlank { "NOUVEAU" },
                        subtitle = if (release.added > 1) "${release.added} nouveaux épisodes" else "Nouvel épisode",
                    ) { nav.open(release.item) }
                }
            }
        } else if (!showHistory) {
            if (library.favorites.isEmpty()) {
                MessageBox("Aucun favori", "Ajoutez des titres depuis leur fiche avec « Ajouter aux favoris ».")
            } else grid {
                items(library.favorites, key = { it.apiName + it.url }) { item ->
                    PosterCard(item.name, item.posterUrl, subtitle = item.apiName) { nav.open(item) }
                }
            }
        } else {
            if (library.history.isEmpty()) {
                MessageBox("Historique vide", "Les titres que vous lancez apparaîtront ici.")
            } else grid {
                items(library.history, key = { it.item.apiName + it.item.url }) { entry ->
                    Box {
                        PosterCard(
                            entry.item.name,
                            entry.item.posterUrl,
                            subtitle = listOfNotNull(
                                entry.episodeLabel?.substringBefore("  "),
                                dateFormat.format(Instant.ofEpochMilli(entry.timestamp)),
                            ).joinToString(" · "),
                        ) { nav.open(entry.item) }
                        FilledTonalIconButton(
                            onClick = { Store.removeHistory(entry) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CsColors.Background.copy(alpha = 0.7f)),
                        ) { Icon(Icons.Default.Close, "Retirer") }
                    }
                }
            }
        }
    }
}

/** "En cours" and "À voir" lists of the connected AniList, MyAnimeList, Simkl and Trakt accounts. */
@Composable
private fun TrackerLists() {
    val nav = LocalNavigator.current
    var lists by remember { mutableStateOf<List<Pair<String, Result<List<com.cloudstreampc.sync.TrackedTitle>>>>?>(null) }
    LaunchedEffect(Unit) {
        lists = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.cloudstreampc.sync.Trackers.connected().map { tracker ->
                tracker.name to runCatching { tracker.list(com.cloudstreampc.sync.Trackers.account(tracker)!!) }
            }
        }
    }
    val loaded = lists
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        loaded.forEach { (name, result) ->
            item(key = "header:$name", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    result.exceptionOrNull()?.let { Text(it.message ?: "Erreur", color = CsColors.Error, fontSize = 13.sp) }
                    if (result.getOrNull()?.isEmpty() == true) Text("Liste vide", color = CsColors.TextMuted, fontSize = 13.sp)
                }
            }
            val titles = result.getOrDefault(emptyList())
            items(titles.size, key = { "$name:$it" }) { index ->
                val title = titles[index]
                PosterCard(title.title, title.posterUrl, subtitle = title.detail) {
                    SearchState.query = com.cloudstreampc.providers.Cinemeta.cleanTitle(title.title)
                    SearchState.pendingSearch = true
                    nav.select(Tab.Search)
                }
            }
        }
    }
}
