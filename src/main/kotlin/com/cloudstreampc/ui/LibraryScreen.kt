package com.cloudstreampc.ui

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
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val library = Store.library

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bibliothèque") {
            if (showHistory && library.history.isNotEmpty()) {
                TextButton(onClick = { Store.clearHistory() }) { Text("Effacer l'historique") }
            }
        }
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!showHistory, { showHistory = false }, { Text("Favoris (${library.favorites.size})") })
            FilterChip(showHistory, { showHistory = true }, { Text("Historique (${library.history.size})") })
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

        if (!showHistory) {
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
