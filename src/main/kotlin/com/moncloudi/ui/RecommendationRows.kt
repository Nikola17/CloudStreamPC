package com.moncloudi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moncloudi.core.RecItem
import com.moncloudi.core.Recommender
import com.moncloudi.core.Store
import com.moncloudi.core.Taste
import com.moncloudi.plugins.PluginManager
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

/** Kinds of content recommendations are made of: not live TV or sports events. */
private val recommendable = setOf(
    TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon,
    TvType.Documentary, TvType.AsianDrama, TvType.Torrent,
)

/**
 * The only two recommendation rows of the home page, computed locally: "Recommandé pour vous", and
 * "Parce que vous avez regardé…" for the last film or series launched. Shown on the home of
 * [provider] only when it offers movies, series or anime, made of titles of the kinds it offers
 * (nothing on a live TV extension), never older than [Recommender.MIN_YEAR].
 */
@Composable
fun RecommendationRows(provider: MainAPI) {
    if (!Store.settings.recommendationsEnabled) return
    val kinds = provider.supportedTypes.filter { it in recommendable }.map { it.name }.toSet()
    if (kinds.isEmpty()) return
    val taste = Taste.current()
    val library = Store.library
    val loaded = PluginManager.providers.map { it.name }.toSet()
    val (forYou, because) = remember(taste, library, loaded, kinds) {
        fun fits(item: RecItem) = item.apiName in loaded && Recommender.recentEnough(item.year) &&
            (item.type?.let { it in kinds } ?: (item.apiName == provider.name))
        val excluded = Recommender.excludedKeys(library)
        val recs = Recommender.recommend(taste, excluded).filter { fits(it.item) }
        val recent = library.history.filter { it.item.type != "Live" }.map { Taste.key(it.item.apiName, it.item.url) }
        val row = Recommender.becauseLastWatched(taste, excluded, recent, keep = ::fits)
        recs to row
    }
    if (forYou.size >= 3) {
        RecRow("Recommandé pour vous", forYou.map { it.item })
    }
    because?.let { row ->
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
                onDismiss = { com.moncloudi.core.EpisodeWatcher.seen(release.item.apiName, release.item.url) },
            ) { nav.open(release.item) }
        }
    }
}
