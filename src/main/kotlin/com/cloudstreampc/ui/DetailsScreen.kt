package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.SavedItem
import com.cloudstreampc.core.Store
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/** Load responses already fetched this session, so going back is instant. */
private val detailsCache = mutableMapOf<String, LoadResponse>()

/** Something that can be played: a movie, an episode or a live channel. */
data class Playable(val data: String, val label: String?, val title: String)

private fun TvType.label() = when (this) {
    TvType.Movie -> "Film"
    TvType.AnimeMovie -> "Film d'animation"
    TvType.TvSeries -> "Série"
    TvType.Anime -> "Animé"
    TvType.OVA -> "OVA"
    TvType.Cartoon -> "Dessin animé"
    TvType.Documentary -> "Documentaire"
    TvType.AsianDrama -> "Drama"
    TvType.Live -> "Direct"
    else -> name
}

private fun DubStatus.label() = when (this) {
    DubStatus.Dubbed -> "Doublé (VF)"
    DubStatus.Subbed -> "Sous-titré (VOSTFR)"
    else -> name
}

fun LoadResponse.toSavedItem() = SavedItem(name, url, apiName, posterUrl, type.name, year)

@Composable
fun DetailsScreen(route: Route.Details) {
    val nav = LocalNavigator.current
    val key = route.apiName + "|" + route.url
    val api = PluginManager.providers.firstOrNull { it.name == route.apiName }
    var response by remember(key) { mutableStateOf(detailsCache[key]) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableStateOf(0) }

    LaunchedEffect(key, attempt) {
        if (response != null || api == null) return@LaunchedEffect
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) { api.load(route.url) }
            if (loaded == null) error = "L'extension n'a rien renvoyé." else {
                detailsCache[key] = loaded
                response = loaded
            }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    Box(Modifier.fillMaxSize().background(CsColors.Background)) {
        val loaded = response
        when {
            api == null -> MessageBox("Extension « ${route.apiName} » non chargée", "Réinstallez-la depuis l'onglet Extensions.")
            loaded != null -> DetailsContent(api, loaded)
            error != null -> MessageBox("Impossible d'ouvrir « ${route.name} »", error) {
                FilledTonalButton(onClick = { attempt++ }) { Text("Réessayer") }
            }
            else -> LoadingBox("Chargement de « ${route.name} »…")
        }
        FilledTonalIconButton(onClick = { nav.back() }, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsContent(api: MainAPI, data: LoadResponse) {
    val nav = LocalNavigator.current
    var playing by remember { mutableStateOf<Playable?>(null) }
    val saved = data.toSavedItem()

    // Episodes, grouped by dub status (animes) then by season.
    val episodesByDub: Map<DubStatus?, List<Episode>> = when (data) {
        is TvSeriesLoadResponse -> mapOf(null to data.episodes)
        is AnimeLoadResponse -> data.episodes.mapKeys { it.key as DubStatus? }.filterValues { it.isNotEmpty() }
        else -> emptyMap()
    }
    var dub by remember(data) { mutableStateOf(episodesByDub.keys.firstOrNull()) }
    val episodes = episodesByDub[dub].orEmpty()
    val seasons = episodes.map { it.season }.distinct().sortedBy { it ?: Int.MAX_VALUE }
    val seasonNames = (data as? TvSeriesLoadResponse)?.seasonNames ?: (data as? AnimeLoadResponse)?.seasonNames
    val lastWatched = Store.library.history.firstOrNull { it.item.apiName == data.apiName && it.item.url == data.url }
    var season by remember(data, dub) {
        mutableStateOf(
            episodes.firstOrNull { it.data == lastWatched?.episodeData }?.season ?: seasons.firstOrNull()
        )
    }

    fun seasonLabel(number: Int?): String {
        if (number == null) return "Épisodes"
        val custom = seasonNames?.firstOrNull { it.season == number }
        return custom?.name ?: "Saison ${custom?.displaySeason ?: number}"
    }

    fun episodeLabel(ep: Episode, index: Int): String {
        val number = ep.episode ?: (index + 1)
        val prefix = if (ep.season != null) "S${ep.season}·E$number" else "Épisode $number"
        return if (ep.name.isNullOrBlank()) prefix else "$prefix  ${ep.name}"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                val backdrop = data.backgroundPosterUrl ?: data.posterUrl
                RemoteImage(
                    backdrop, Modifier.fillMaxWidth().height(420.dp), data.posterHeaders,
                    placeholder = { Box(Modifier.fillMaxWidth().height(420.dp).background(CsColors.Surface)) },
                )
                Box(
                    Modifier.fillMaxWidth().height(420.dp).background(
                        Brush.verticalGradient(listOf(CsColors.Background.copy(alpha = 0.55f), CsColors.Background))
                    )
                )
                Row(Modifier.padding(start = 40.dp, end = 40.dp, top = 90.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RemoteImage(
                        data.posterUrl,
                        Modifier.width(200.dp).height(300.dp).clip(RoundedCornerShape(12.dp)),
                        data.posterHeaders,
                        if (data is LiveStreamLoadResponse) ContentScale.Fit else ContentScale.Crop,
                        placeholder = { Box(Modifier.width(200.dp).height(300.dp).clip(RoundedCornerShape(12.dp)).background(CsColors.SurfaceHigh)) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(data.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        val meta = listOfNotNull(
                            data.type.label(),
                            data.year?.toString(),
                            data.duration?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                            data.contentRating,
                            api.name,
                        )
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            data.score?.let { score ->
                                Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
                                Text(" %.1f   ".format(score.toDouble(10)), fontWeight = FontWeight.SemiBold)
                            }
                            Text(meta.joinToString("  ·  "), color = CsColors.TextMuted)
                        }
                        if (!data.tags.isNullOrEmpty()) {
                            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                data.tags!!.take(8).forEach { AssistChip(onClick = {}, label = { Text(it, fontSize = 12.sp) }) }
                            }
                        }
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val mainPlayable: Playable? = when (data) {
                                is MovieLoadResponse -> Playable(data.dataUrl, null, data.name)
                                is LiveStreamLoadResponse -> Playable(data.dataUrl, null, data.name)
                                else -> {
                                    val resume = episodes.withIndex().firstOrNull { it.value.data == lastWatched?.episodeData }
                                    val target = resume ?: episodes.withIndex().firstOrNull()
                                    target?.let { Playable(it.value.data, episodeLabel(it.value, it.index), data.name) }
                                }
                            }
                            if (mainPlayable != null) {
                                Button(onClick = { playing = mainPlayable }) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        when {
                                            data is LiveStreamLoadResponse -> "Regarder en direct"
                                            mainPlayable.label == null -> "Lire"
                                            lastWatched?.episodeData == mainPlayable.data -> "Reprendre ${mainPlayable.label.substringBefore("  ")}"
                                            else -> "Lire ${mainPlayable.label.substringBefore("  ")}"
                                        }
                                    )
                                }
                            }
                            val favorite = Store.isFavorite(data.apiName, data.url)
                            FilledTonalButton(onClick = {
                                Store.toggleFavorite(saved)
                                nav.toast(if (favorite) "Retiré des favoris" else "Ajouté aux favoris")
                            }) {
                                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (favorite) "Favori" else "Ajouter aux favoris")
                            }
                            TextButton(onClick = { openInBrowser(data.url) }) {
                                Icon(AppIcons.OpenInBrowser, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Site")
                            }
                        }
                        if (data is TorrentLoadResponse) {
                            Text("Les torrents ne sont pas encore pris en charge.", color = CsColors.Error, modifier = Modifier.padding(top = 8.dp))
                        }
                        data.plot?.takeIf { it.isNotBlank() }?.let {
                            Text(it, modifier = Modifier.padding(top = 16.dp).widthIn(max = 900.dp), color = Color(0xFFDDDDDD), lineHeight = 22.sp)
                        }
                        data.actors?.takeIf { it.isNotEmpty() }?.let { actors ->
                            Text(
                                "Avec : " + actors.take(10).joinToString(", ") { it.actor.name },
                                color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (episodes.isNotEmpty() || episodesByDub.size > 1) {
            item {
                Column(Modifier.padding(horizontal = 40.dp, vertical = 8.dp)) {
                    if (episodesByDub.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            episodesByDub.keys.forEach { status ->
                                FilterChip(dub == status, { dub = status }, { Text(status?.label() ?: "Tous") })
                            }
                        }
                    }
                    if (seasons.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            seasons.forEach { s -> FilterChip(season == s, { season = s }, { Text(seasonLabel(s)) }) }
                        }
                    } else {
                        Text("${episodes.size} épisode${if (episodes.size > 1) "s" else ""}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            val visible = episodes.withIndex().filter { seasons.size <= 1 || it.value.season == season }
            items(visible, key = { "${dub}/${it.index}/${it.value.data}" }) { (index, ep) ->
                val label = episodeLabel(ep, index)
                EpisodeRow(
                    ep, label,
                    watched = Store.watchedKey(data.apiName, ep.data) in Store.library.watched,
                    current = lastWatched?.episodeData == ep.data,
                ) { playing = Playable(ep.data, label, data.name) }
            }
        }

        data.recommendations?.takeIf { it.isNotEmpty() }?.let { recs ->
            item {
                Spacer(Modifier.height(24.dp))
                PosterRow("Recommandations", recs, onOpen = nav::open)
            }
        }
    }

    playing?.let { target ->
        LinksDialog(api, data, target, onDismiss = { playing = null })
    }
}

@Composable
private fun EpisodeRow(ep: Episode, label: String, watched: Boolean, current: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) CsColors.SurfaceHigh else if (current) CsColors.Surface else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(8.dp)).background(CsColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            if (ep.posterUrl != null) RemoteImage(ep.posterUrl, Modifier.fillMaxSize())
            Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(alpha = if (hovered) 1f else 0.7f), modifier = Modifier.size(36.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ep.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CsColors.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ep.runTime?.let { Text("$it min", color = CsColors.TextMuted, fontSize = 12.sp) }
        }
        if (watched) Icon(Icons.Default.CheckCircle, "Vu", tint = CsColors.Good)
    }
}

fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
