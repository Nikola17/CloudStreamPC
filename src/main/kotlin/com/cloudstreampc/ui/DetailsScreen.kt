package com.cloudstreampc.ui

import com.cloudstreampc.player.ExternalPlayer

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cloudstreampc.download.DownloadManager
import com.cloudstreampc.player.PlaybackManager
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.player.Playable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/** Load responses already fetched this session, so going back is instant. */
object DetailsCache {
    private val cache = java.util.Collections.synchronizedMap(mutableMapOf<String, LoadResponse>())

    fun cached(apiName: String, url: String): LoadResponse? = cache["$apiName|$url"]

    fun clear() = cache.clear()

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        cached(api.name, url)?.let { return it }
        return withContext(Dispatchers.IO) { api.load(url) }?.also { cache["${api.name}|$url"] = it }
    }
}


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
    var response by remember(key) { mutableStateOf(DetailsCache.cached(route.apiName, route.url)) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableStateOf(0) }

    LaunchedEffect(key, attempt) {
        if (response != null || api == null) return@LaunchedEffect
        error = null
        try {
            val loaded = DetailsCache.load(api, route.url)
            if (loaded == null) error = "L'extension n'a rien renvoyé." else response = loaded
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
    val saved = data.toSavedItem()

    // Episodes, grouped by dub status (animes) then by season.
    val episodesByDub: Map<DubStatus?, List<Episode>> = PlayQueue.episodesByDub(data)
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

    // Everything playable from this page, in order, so the next episode can follow.
    val queue: List<Playable> = PlayQueue.build(data, episodes)

    var trailer by remember(data) { mutableStateOf<String?>(null) }
    LaunchedEffect(data) { trailer = runCatching { com.cloudstreampc.providers.Trailers.youtubeId(data) }.getOrNull() }

    // The user sees the episode list: remember its size and clear the "new episode" mark.
    LaunchedEffect(data) {
        withContext(Dispatchers.IO) {
            if (episodesByDub.isNotEmpty()) com.cloudstreampc.core.EpisodeWatcher.record(saved, data)
            com.cloudstreampc.core.EpisodeWatcher.seen(data.apiName, data.url)
        }
    }

    val scope = rememberCoroutineScope()

    /** Plays with the best source right away (setting), or [chooseSource] to pick it by hand. */
    fun play(index: Int, chooseSource: Boolean = false) {
        if (index !in queue.indices) return
        val request = PlayRequest(api, data, queue, index)
        PlaybackManager.request(if (chooseSource) request.copy(chooseManually = true) else request)
    }

    fun download(index: Int) {
        if (index !in queue.indices || data is LiveStreamLoadResponse) return
        nav.toast("Recherche de la meilleure source à télécharger…")
        scope.launch {
            val error = DownloadManager.download(PlayRequest(api, data, queue, index))
            nav.toast(error ?: "Ajouté aux téléchargements")
        }
    }

    fun seasonLabel(number: Int?): String {
        if (number == null) return "Épisodes"
        val custom = seasonNames?.firstOrNull { it.season == number }
        return custom?.name ?: "Saison ${custom?.displaySeason ?: number}"
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
                            // Last episode launched; once it is watched, the next one is proposed.
                            val mainIndex = PlayQueue.startIndex(data, queue)
                            if (mainIndex >= 0) {
                                val target = queue[mainIndex]
                                val progress = Store.progressOf(data.apiName, target.data)?.takeIf { it.position > 15 }
                                val episode = target.label?.substringBefore("  ")
                                Button(onClick = { play(mainIndex) }) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        when {
                                            data is LiveStreamLoadResponse -> "Regarder en direct"
                                            progress != null -> listOfNotNull("Reprendre", episode, "(${formatTime(progress.position)})").joinToString(" ")
                                            episode != null -> "Lire $episode"
                                            else -> "Lire"
                                        }
                                    )
                                }
                            }
                            if (mainIndex >= 0) {
                                FilledTonalButton(onClick = { play(mainIndex, chooseSource = true) }) { Text("Sources") }
                                if (data !is LiveStreamLoadResponse) {
                                    val existing = DownloadManager.find(data.apiName, queue[mainIndex].data)
                                    FilledTonalButton(onClick = { download(mainIndex) }) {
                                        Icon(AppIcons.Download, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (existing != null) "Téléchargé" else "Télécharger")
                                    }
                                }
                            }
                            trailer?.let { id ->
                                FilledTonalButton(onClick = {
                                    runCatching { ExternalPlayer.playTrailer(com.cloudstreampc.providers.Trailers.youtubeUrl(id), "${data.name} · Bande-annonce") }
                                        .onFailure { nav.toast(it.message ?: "Impossible de lancer le lecteur") }
                                }) {
                                    Icon(AppIcons.VideoLibrary, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Bande-annonce")
                                }
                            }
                            val favorite = Store.isFavorite(data.apiName, data.url)
                            FilledTonalButton(onClick = {
                                Store.toggleFavorite(saved)
                                com.cloudstreampc.core.Taste.recordFavorite(data, added = !favorite)
                                com.cloudstreampc.providers.TasteEnricher.enrichSoon()
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
                        data.actors?.takeIf { it.isNotEmpty() && Store.settings.showCast }?.let { actors ->
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
                val watched = Store.isWatched(data.apiName, ep.data)
                EpisodeRow(
                    ep, queue[index].label.orEmpty(),
                    watched = watched,
                    progress = Store.progressOf(data.apiName, ep.data)?.fraction,
                    current = lastWatched?.episodeData == ep.data,
                    downloaded = DownloadManager.find(data.apiName, ep.data) != null,
                    onToggleWatched = { Store.setWatched(data.apiName, ep.data, !watched) },
                    onDownload = { download(index) },
                ) { play(index) }
            }
        }

        data.recommendations?.takeIf { it.isNotEmpty() }?.let { recs ->
            item {
                Spacer(Modifier.height(24.dp))
                PosterRow("Recommandations", recs, onOpen = nav::open)
            }
        }
    }

}

@Composable
private fun EpisodeRow(
    ep: Episode,
    label: String,
    watched: Boolean,
    progress: Float?,
    current: Boolean,
    downloaded: Boolean,
    onToggleWatched: () -> Unit,
    onDownload: () -> Unit,
    onClick: () -> Unit,
) {
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
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = CsColors.Primary,
                    trackColor = Color.Black.copy(alpha = 0.5f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ep.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CsColors.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ep.runTime?.let { Text("$it min", color = CsColors.TextMuted, fontSize = 12.sp) }
        }
        IconButton(onClick = onDownload) {
            Icon(
                if (downloaded) AppIcons.Download else AppIcons.DownloadOutlined,
                "Télécharger",
                tint = if (downloaded) CsColors.Primary else CsColors.TextMuted.copy(alpha = if (hovered) 0.9f else 0.35f),
            )
        }
        IconButton(onClick = onToggleWatched) {
            Icon(
                Icons.Default.CheckCircle,
                if (watched) "Marquer comme non vu" else "Marquer comme vu",
                tint = if (watched) CsColors.Good else CsColors.TextMuted.copy(alpha = if (hovered) 0.8f else 0.25f),
            )
        }
    }
}

fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
