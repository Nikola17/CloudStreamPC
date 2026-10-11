package com.cloudstreampc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.RecItem
import com.cloudstreampc.core.Recommender
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.Taste
import com.cloudstreampc.plugins.PluginManager

/** "Recommandé pour vous" and "Parce que vous avez regardé…" rows, computed locally. */
@Composable
fun RecommendationRows() {
    if (!Store.settings.recommendationsEnabled) return
    val taste = Taste.current()
    val library = Store.library
    val loaded = PluginManager.providers.map { it.name }.toSet()
    val (forYou, because) = remember(taste, library, loaded) {
        val excluded = Recommender.excludedKeys(library)
        val recs = Recommender.recommend(taste, excluded).filter { it.item.apiName in loaded }
        val rows = Recommender.becauseRows(taste, excluded)
            .map { it.copy(items = it.items.filter { item -> item.apiName in loaded }) }
            .filter { it.items.size >= 5 }
        recs to rows
    }
    if (forYou.size >= 3) {
        RecRow("Recommandé pour vous", forYou.map { it.item })
    }
    because.forEach { row ->
        RecRow("Parce que vous avez regardé ${row.seed.name}", row.items)
    }
}

@Composable
private fun RecRow(title: String, items: List<RecItem>) {
    val nav = LocalNavigator.current
    MediaRow(title) {
        items(items, key = { it.apiName + it.url }) { item ->
            PosterCard(
                item.name,
                item.posterUrl,
                score = item.rating?.takeIf { it > 0 }?.let { "%.1f".format(it).removeSuffix(",0").removeSuffix(".0") },
                onDismiss = { Taste.dismiss(item.apiName, item.url) },
            ) { nav.push(Route.Details(item.apiName, item.url, item.name, item.posterUrl)) }
        }
    }
}

/** Followed series with episodes released since the user last opened them. */
@Composable
fun NewEpisodesRow() {
    val releases = Store.library.newEpisodes
    if (releases.isEmpty()) return
    val nav = LocalNavigator.current
    MediaRow("Nouveaux épisodes") {
        items(releases, key = { "new:" + it.item.apiName + it.item.url }) { release ->
            PosterCard(
                release.item.name,
                release.item.posterUrl,
                badge = release.label.ifBlank { "NOUVEAU" },
                onDismiss = { com.cloudstreampc.core.EpisodeWatcher.seen(release.item.apiName, release.item.url) },
            ) { nav.open(release.item) }
        }
    }
}
