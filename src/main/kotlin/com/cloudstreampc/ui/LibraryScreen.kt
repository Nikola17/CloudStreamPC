package com.cloudstreampc.ui

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.clickable

import androidx.compose.ui.unit.sp

import androidx.compose.runtime.remember

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester

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

private enum class ListSort(val label: String) { Added("Ajout récent"), Title("Titre"), Year("Année") }

private enum class TypeFilter(val label: String, val types: Set<String>?) {
    All("Tout", null),
    Movies("Films", setOf("Movie", "AnimeMovie", "Documentary", "Torrent")),
    Series("Séries", setOf("TvSeries", "AsianDrama", "Cartoon")),
    Anime("Animés", setOf("Anime", "OVA", "AnimeMovie")),
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen() {
    val nav = LocalNavigator.current
    val library = Store.library
    // "new" (opened first when there are new episodes, like the sidebar badge says), "list", "history",
    // "tracking", or "c:<collection id>".
    var section by rememberSaveable { mutableStateOf(if (library.newEpisodes.isNotEmpty()) "new" else "list") }
    if (section == "new" && library.newEpisodes.isEmpty()) section = "list"
    if (section.startsWith("c:") && library.collections.none { "c:" + it.id == section }) section = "list"
    val statusSection = com.cloudstreampc.core.WatchStatus.entries.firstOrNull { "s:" + it.name == section }
    if (statusSection != null && library.statuses.values.none { it.status == statusSection }) section = "list"
    var sort by rememberSaveable { mutableStateOf(ListSort.Added) }
    var filter by rememberSaveable { mutableStateOf(TypeFilter.All) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<com.cloudstreampc.core.UserCollection?>(null) }
    val collection = library.collections.firstOrNull { "c:" + it.id == section }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bibliothèque") {
            if (section == "history" && library.history.isNotEmpty()) {
                TextButton(onClick = { Store.clearHistory() }) { Text("Effacer l'historique") }
            }
            if (collection != null) {
                TextButton(onClick = { renaming = collection }) { Text("Renommer") }
                TextButton(onClick = { Store.deleteCollection(collection.id) }) { Text("Supprimer la collection") }
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (library.newEpisodes.isNotEmpty()) {
                FilterChip(section == "new", { section = "new" }, { Text("Nouveaux épisodes (${library.newEpisodes.size})") })
            }
            FilterChip(section == "list", { section = "list" }, { Text("Ma liste (${library.favorites.size})") })
            com.cloudstreampc.core.WatchStatus.entries.forEach { status ->
                val count = library.statuses.values.count { it.status == status }
                if (count > 0) FilterChip(section == "s:" + status.name, { section = "s:" + status.name }, { Text(status.shown + " ($count)") })
            }
            library.collections.forEach { c ->
                FilterChip(section == "c:" + c.id, { section = "c:" + c.id }, { Text("${c.name} (${c.items.size})") })
            }
            FilterChip(false, { creating = true }, { Text("+ Collection") })
            FilterChip(section == "history", { section = "history" }, { Text("Historique (${library.history.size})") })
            if (com.cloudstreampc.sync.Trackers.connected().isNotEmpty()) {
                FilterChip(section == "tracking", { section = "tracking" }, { Text("Listes de suivi") })
            }
        }
        if (section == "list" || collection != null || statusSection != null) {
            Row(Modifier.padding(start = 24.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Trier :", color = CsColors.TextMuted, fontSize = 13.sp)
                ListSort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label, fontSize = 12.sp) }) }
                Text("   Afficher :", color = CsColors.TextMuted, fontSize = 13.sp)
                TypeFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label, fontSize = 12.sp) }) }
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
        fun arranged(items: List<com.cloudstreampc.core.SavedItem>) = items
            .filter { filter.types == null || it.type in filter.types!! }
            .let { list ->
                when (sort) {
                    ListSort.Added -> list
                    ListSort.Title -> list.sortedBy { java.text.Normalizer.normalize(it.name.lowercase(), java.text.Normalizer.Form.NFD) }
                    ListSort.Year -> list.sortedByDescending { it.year ?: 0 }
                }
            }

        when {
            section == "tracking" -> TrackerLists()
            section == "new" -> grid {
                items(library.newEpisodes, key = { "new:" + it.item.apiName + it.item.url }) { release ->
                    PosterCard(
                        release.item.name, release.item.posterUrl,
                        badge = release.label.ifBlank { "NOUVEAU" },
                        subtitle = if (release.added > 1) "${release.added} nouveaux épisodes" else "Nouvel épisode",
                    ) { nav.open(release.item) }
                }
            }
            statusSection != null -> {
                val entries = library.statuses.values.filter { it.status == statusSection }.sortedByDescending { it.updatedAt }
                val items = arranged(entries.map { it.item })
                grid {
                    items(items, key = { it.apiName + it.url }) { item ->
                        PosterCard(item.name, item.posterUrl, subtitle = item.year?.toString() ?: item.apiName, onDismiss = { Store.setStatus(item, null) }) {
                            nav.open(item)
                        }
                    }
                }
            }
            collection != null -> {
                val items = arranged(collection.items)
                if (items.isEmpty()) {
                    MessageBox("Collection vide", "Ajoutez des titres depuis leur fiche avec le bouton « Collections ».")
                } else grid {
                    items(items, key = { it.apiName + it.url }) { item ->
                        PosterCard(item.name, item.posterUrl, subtitle = item.year?.toString(), onDismiss = { Store.toggleInCollection(collection.id, item) }) {
                            nav.open(item)
                        }
                    }
                }
            }
            section == "list" -> {
                val items = arranged(library.favorites)
                if (library.favorites.isEmpty()) {
                    MessageBox("Ma liste est vide", "Ajoutez des titres avec « + » sur l'accueil ou « Ajouter aux favoris » sur leur fiche.")
                } else grid {
                    items(items, key = { it.apiName + it.url }) { item ->
                        PosterCard(item.name, item.posterUrl, subtitle = item.year?.toString() ?: item.apiName) { nav.open(item) }
                    }
                }
            }
            else -> {
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

    if (creating) {
        NameDialog("Nouvelle collection", "", onDismiss = { creating = false }) { name ->
            section = "c:" + Store.createCollection(name).id
            creating = false
        }
    }
    renaming?.let { c ->
        NameDialog("Renommer la collection", c.name, onDismiss = { renaming = null }) { name ->
            Store.renameCollection(c.id, name)
            renaming = null
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                name, { name = it }, label = { Text("Nom") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
                modifier = Modifier.padding(top = 4.dp).focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Adds or removes a title from the user's collections (from its details page). */
@Composable
fun CollectionsDialog(item: com.cloudstreampc.core.SavedItem, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val collections = Store.library.collections
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column {
                if (collections.isEmpty()) Text("Aucune collection pour l'instant.", color = CsColors.TextMuted, fontSize = 13.sp)
                collections.forEach { c ->
                    val inside = c.items.any { it.apiName == item.apiName && it.url == item.url }
                    Row(
                        Modifier.fillMaxWidth().clickable { Store.toggleInCollection(c.id, item) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(inside, { Store.toggleInCollection(c.id, item) })
                        Text(c.name)
                        Text("  ${c.items.size}", color = CsColors.TextMuted, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { creating = true }) { Text("+ Nouvelle collection") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } },
    )
    if (creating) {
        NameDialog("Nouvelle collection", "", onDismiss = { creating = false }) { name ->
            Store.toggleInCollection(Store.createCollection(name).id, item)
            creating = false
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
