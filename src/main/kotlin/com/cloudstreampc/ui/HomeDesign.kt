package com.cloudstreampc.ui

import androidx.compose.ui.draw.alpha

import com.cloudstreampc.player.PlaybackManager

import com.cloudstreampc.providers.Trailers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.HistoryEntry
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.plugins.PluginManager
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Left edge shared by the hero text and every row, so everything lines up. */
val HomeInset: Dp = 40.dp

private val SoftWhite = Color(0xFFE8E8E8)
private val Muted = Color(0xFFB3B3B3)

/**
 * A horizontal row as on streaming platforms: title, "Tout voir" on hover,
 * and arrows on the sides to scroll it with the mouse.
 */
@Composable
fun MediaRow(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    spacing: Dp = 10.dp,
    content: LazyListScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsActiveAsState()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(bottom = 30.dp).hoverable(interaction)) {
        Row(Modifier.fillMaxWidth().padding(start = HomeInset, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = SoftWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onSeeAll != null) {
                AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        "Tout voir  ›", style = MaterialTheme.typography.labelLarge, color = Color(0xFF54B9C5),
                        modifier = Modifier.padding(start = 14.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onSeeAll).padding(4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSeeAll) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Tout voir", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth()) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = HomeInset, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
            val step = { (state.layoutInfo.viewportSize.width * 0.8f) }
            if (hovered && state.canScrollBackward) {
                RowArrow(Modifier.align(Alignment.CenterStart), left = true) { scope.launch { state.animateScrollBy(-step()) } }
            }
            if (hovered && state.canScrollForward) {
                RowArrow(Modifier.align(Alignment.CenterEnd), left = false) { scope.launch { state.animateScrollBy(step()) } }
            }
        }
    }
}

@Composable
private fun RowArrow(modifier: Modifier, left: Boolean, onClick: () -> Unit) {
    val background = CsColors.Background
    Box(
        modifier.width(56.dp).height(200.dp)
            .background(
                Brush.horizontalGradient(
                    if (left) listOf(background.copy(alpha = 0.95f), Color.Transparent) else listOf(Color.Transparent, background.copy(alpha = 0.95f))
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            if (left) "Précédent" else "Suivant",
            tint = Color.White, modifier = Modifier.size(40.dp),
        )
    }
}

/** Netflix-like "Top 10": large ranks behind the posters' left edge. */
@Composable
fun Top10Row(title: String, items: List<SearchResponse>, onOpen: (SearchResponse) -> Unit) {
    if (items.size < 5) return
    val posterHeight = posterWidth * 1.5f
    MediaRow(title, spacing = 6.dp) {
        itemsIndexed(items.take(10), key = { _, it -> "top/" + it.url }) { index, item ->
            Box(Modifier.width(posterWidth + 96.dp).height(posterHeight + 12.dp).clipToBounds()) {
                Text(
                    "${index + 1}",
                    style = TextStyle(
                        fontFamily = AppFonts.Display, fontWeight = FontWeight.ExtraBold,
                        fontSize = (posterHeight.value * 0.92f).sp, lineHeight = (posterHeight.value * 0.92f).sp,
                        letterSpacing = (-0.08).em,
                        brush = Brush.verticalGradient(listOf(Color(0xFF6A6A6A), Color(0xFF242424))),
                    ),
                    modifier = Modifier.align(Alignment.BottomStart).offset(x = (-6).dp, y = (posterHeight.value * 0.16f).dp),
                )
                Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp)) {
                    SearchResponseCard(item, showTitle = false, onOpen = onOpen)
                }
            }
        }
    }
}

/** "Continuer à regarder" in landscape cards; a click resumes playback directly. */
@Composable
fun ContinueWatchingRow(entries: List<HistoryEntry>) {
    if (entries.isEmpty()) return
    MediaRow("Continuer à regarder") {
        items(entries, key = { "cw/" + it.item.apiName + it.item.url }) { entry -> ContinueCard(entry) }
    }
}

@Composable
private fun ContinueCard(entry: HistoryEntry) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsActiveAsState()
    val api = PluginManager.providers.firstOrNull { it.name == entry.item.apiName }
    var details by remember(entry.item.url) { mutableStateOf(DetailsCache.cached(entry.item.apiName, entry.item.url)) }
    LaunchedEffect(entry.item.url) {
        if (details == null && api != null) details = runCatching { DetailsCache.load(api, entry.item.url) }.getOrNull()
    }
    val progress = entry.episodeData?.let { Store.progressOf(entry.item.apiName, it) }
    val next = entry.episodeData != null && Store.isWatched(entry.item.apiName, entry.episodeData)
    val subtitle = when {
        next -> "Épisode suivant"
        progress != null -> listOfNotNull(
            entry.episodeLabel?.substringBefore("  "),
            "${((progress.duration - progress.position) / 60).toInt().coerceAtLeast(1)} min restantes",
        ).joinToString("  ·  ")
        else -> entry.episodeLabel?.substringBefore("  ") ?: entry.item.apiName
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(320.dp).hoverable(interaction).clickable(interaction, indication = null) {
            val loaded = details
            if (api != null && loaded != null) PlayQueue.play(api, loaded) else nav.open(entry.item)
        }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).scale(if (hovered) 1.03f else 1f).clip(shape)
                .background(CsColors.Surface).border(2.dp, if (hovered) Color.White else Color.Transparent, shape)
        ) {
            RemoteImage(details?.backgroundPosterUrl ?: entry.item.posterUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            if (hovered) {
                Box(
                    Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.PlayArrow, "Reprendre", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)).clickable { nav.open(entry.item) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Info, "Infos", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(entry.item.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = CsColors.Primary,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

/** Big featured title at the top of the home page, rotating every 10 s (paused on hover). */
@Composable
fun HeroCarousel(api: MainAPI, items: List<SearchResponse>, height: Dp) {
    val nav = LocalNavigator.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsActiveAsState()
    var index by remember(items) { mutableIntStateOf(0) }
    val item = items[index % items.size]
    var details by remember(item.url) { mutableStateOf(DetailsCache.cached(api.name, item.url)) }
    LaunchedEffect(item.url) {
        if (details == null) details = runCatching { DetailsCache.load(api, item.url) }.getOrNull()
    }
    // Trailer without sound after a few seconds on the same title, like the streaming platforms.
    var trailerUrl by remember(item.url) { mutableStateOf<String?>(null) }
    val trailerAlpha = remember(item.url) { Animatable(0f) }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val allowTrailer = Store.settings.heroTrailers && focused && PlaybackManager.nowPlaying == null
    LaunchedEffect(item.url, details, allowTrailer) {
        val loaded = details
        if (!allowTrailer) {
            trailerUrl = null
            return@LaunchedEffect
        }
        if (loaded == null || trailerUrl != null) return@LaunchedEffect
        delay(4_000)
        val id = runCatching { Trailers.youtubeId(loaded) }.getOrNull() ?: return@LaunchedEffect
        trailerUrl = Trailers.silentStream(id)
    }
    val trailerPlaying = trailerUrl != null

    // Slow zoom on the backdrop, and a progress bar on the current indicator.
    val zoom = remember(item.url) { Animatable(1f) }
    val progress = remember(item.url) { Animatable(0f) }
    LaunchedEffect(item.url, hovered, trailerPlaying) {
        // The trailer plays to its end before the next title.
        if (hovered || trailerPlaying) return@LaunchedEffect
        launch { zoom.animateTo(1.08f, tween(14_000, easing = LinearEasing)) }
        progress.animateTo(1f, tween(((1f - progress.value) * 10_000).toInt(), easing = LinearEasing))
        delay(200)
        index = (index + 1) % items.size
    }
    val response: LoadResponse? = details
    val background = CsColors.Background

    Box(Modifier.fillMaxWidth().height(height).clipToBounds().hoverable(interaction)) {
        Crossfade(response?.backgroundPosterUrl ?: response?.posterUrl ?: item.posterUrl, animationSpec = tween(700)) { image ->
            RemoteImage(image, Modifier.fillMaxSize().scale(zoom.value), item.posterHeaders, alignment = Alignment.TopCenter,
                placeholder = { Box(Modifier.fillMaxSize().background(CsColors.Surface)) })
        }
        trailerUrl?.let { url ->
            val scope = rememberCoroutineScope()
            SilentVideo(
                url, Modifier.fillMaxSize().alpha(trailerAlpha.value),
                onFirstFrame = { scope.launch { trailerAlpha.animateTo(1f, tween(900)) } },
                onEnded = {
                    scope.launch {
                        // Played to the end: next title. Failed to start: back to the picture.
                        val played = trailerAlpha.value > 0f
                        trailerAlpha.animateTo(0f, tween(600))
                        trailerUrl = null
                        if (played) index = (index + 1) % items.size
                    }
                },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background.copy(alpha = 0.92f), 0.45f to background.copy(alpha = 0.35f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to background.copy(alpha = 0.5f), 0.18f to Color.Transparent, 0.62f to Color.Transparent, 1f to background)))

        Column(Modifier.align(Alignment.BottomStart).padding(start = HomeInset, bottom = 64.dp).widthIn(max = 620.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(16.dp).background(CsColors.Primary))
                Text(
                    listOfNotNull(typeLabel(response?.type ?: item.type), api.name.uppercase()).joinToString("   ·   "),
                    style = MaterialTheme.typography.labelMedium, color = SoftWhite,
                    letterSpacing = 0.22.em, modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            val logo = response?.logoUrl
            if (logo != null) {
                RemoteImage(
                    logo, Modifier.heightIn(max = 150.dp).widthIn(max = 520.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    placeholder = { HeroTitle(item.name) },
                )
            } else {
                HeroTitle(item.name)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                (response?.score ?: item.score)?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(17.dp))
                        Text(" %.1f".format(it.toDouble(10)), style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                }
                (response?.year ?: item.yearOrNull())?.let { Text("$it", style = MaterialTheme.typography.titleSmall, color = SoftWhite) }
                response?.duration?.let { Text(if (it >= 60) "${it / 60} h ${"%02d".format(it % 60)}" else "$it min", style = MaterialTheme.typography.titleSmall, color = SoftWhite) }
                response?.contentRating?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = SoftWhite,
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                qualityLabel(item.quality)?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = SoftWhite,
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            response?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                Text(
                    tags.take(3).joinToString("  •  "), style = MaterialTheme.typography.bodyMedium, color = Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            response?.plot?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyLarge, color = SoftWhite, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp).widthIn(max = 560.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroButton("Lecture", Icons.Default.PlayArrow, primary = true) {
                    response?.let { PlayQueue.play(api, it) } ?: nav.open(item)
                }
                HeroButton("Plus d'infos", Icons.Default.Info, primary = false) { nav.open(item) }
                val favorite = Store.isFavorite(api.name, item.url)
                Box(
                    Modifier.size(48.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable {
                            response?.let {
                                Store.toggleFavorite(it.toSavedItem())
                                com.cloudstreampc.core.Taste.recordFavorite(it, added = !favorite)
                                nav.toast(if (favorite) "Retiré de Ma liste" else "Ajouté à Ma liste")
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (favorite) Icons.Default.Check else Icons.Default.Add, "Ma liste", tint = Color.White) }
            }
        }

        // Indicators: one segment per title, the current one fills up.
        Row(Modifier.align(Alignment.BottomEnd).padding(end = HomeInset, bottom = 72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.indices.forEach { i ->
                val current = i == index % items.size
                Box(Modifier.width(30.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)).clickable { index = i }) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(if (current) progress.value else if (i < index % items.size) 1f else 0f)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroTitle(name: String) {
    Text(
        name.replace(Regex("\\s*\\[(VF|VOSTFR)]"), ""),
        style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HeroButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsActiveAsState()
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    primary && hovered -> Color.White.copy(alpha = 0.82f)
                    primary -> Color.White
                    hovered -> Color(0xFF6D6D6E).copy(alpha = 0.55f)
                    else -> Color(0xFF6D6D6E).copy(alpha = 0.4f)
                }
            )
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(start = 20.dp, end = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (primary) Color.Black else Color.White
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), color = color, modifier = Modifier.padding(start = 10.dp))
    }
}

fun typeLabel(type: TvType?): String? = when (type) {
    TvType.Movie -> "FILM"
    TvType.AnimeMovie -> "FILM D'ANIMATION"
    TvType.TvSeries -> "SÉRIE"
    TvType.Anime -> "ANIMÉ"
    TvType.Cartoon -> "DESSIN ANIMÉ"
    TvType.Documentary -> "DOCUMENTAIRE"
    TvType.AsianDrama -> "DRAMA"
    TvType.Live -> "EN DIRECT"
    else -> null
}?.let(I18n::tr)

/** Tabs over the hero to keep only the rows of one kind, like the platforms' top menu. */
enum class HomeTab(val label: String, val types: Set<TvType>?) {
    All("Accueil", null),
    Movies("Films", setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)),
    Series("Séries", setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon)),
    Anime("Animés", setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)),
    Live("TV en direct", setOf(TvType.Live)),
}

@Composable
fun HomeTabs(
    available: List<HomeTab>,
    selected: HomeTab,
    modifier: Modifier = Modifier,
    onGuide: (() -> Unit)? = null,
    onCinema: (() -> Unit)? = null,
    onSelect: (HomeTab) -> Unit,
) {
    if (available.size < 2 && onGuide == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
        available.forEach { tab ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsActiveAsState()
            Column(
                Modifier.hoverable(interaction).clickable(interaction, indication = null) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        tab == selected -> Color.White
                        hovered -> Color(0xFFDDDDDD)
                        else -> Color(0xFFB3B3B3)
                    },
                )
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(22.dp).height(2.dp).background(if (tab == selected) CsColors.Primary else Color.Transparent))
            }
        }
        if (onCinema != null) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsActiveAsState()
            Row(
                Modifier.padding(bottom = 7.dp).clip(RoundedCornerShape(50)).hoverable(interaction)
                    .background(Color.White.copy(alpha = if (hovered) 0.22f else 0.12f))
                    .clickable(interaction, indication = null, onClick = onCinema)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Fullscreen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Mode cinéma", style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp), fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        if (onGuide != null) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsActiveAsState()
            Row(
                Modifier.padding(bottom = 7.dp).clip(RoundedCornerShape(50)).hoverable(interaction)
                    .background(Color.White.copy(alpha = if (hovered) 0.22f else 0.12f))
                    .clickable(interaction, indication = null, onClick = onGuide)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Tv, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Guide TV", style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp), fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/** Placeholder rows while the home page loads (instead of a spinner). */
@Composable
fun SkeletonRows(rows: Int = 3) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        repeat(rows) {
            Box(Modifier.padding(start = HomeInset, bottom = 14.dp).width(220.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).background(CsColors.SurfaceHigh.copy(alpha = alpha)))
            Row(Modifier.padding(start = HomeInset, bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(8) {
                    Box(Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(CsColors.Surface.copy(alpha = alpha)))
                }
            }
        }
    }
}
