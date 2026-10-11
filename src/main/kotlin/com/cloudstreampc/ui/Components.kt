package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Store
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse

fun SearchResponse.yearOrNull(): Int? = when (this) {
    is MovieSearchResponse -> year
    is TvSeriesSearchResponse -> year
    is AnimeSearchResponse -> year
    else -> null
}

/** Poster width from the "Taille des affiches" setting. */
val posterWidth: Dp
    get() = when (Store.settings.posterSize) {
        0 -> 128.dp
        2 -> 200.dp
        else -> 165.dp
    }

fun qualityLabel(quality: SearchQuality?): String? = when (quality) {
    null -> null
    SearchQuality.FourK, SearchQuality.UHD -> "4K"
    SearchQuality.HD, SearchQuality.BlueRay -> "HD"
    SearchQuality.SD -> "SD"
    SearchQuality.Cam, SearchQuality.CamRip, SearchQuality.HdCam -> "CAM"
    SearchQuality.WebRip -> "WEB"
    SearchQuality.HDR -> "HDR"
    SearchQuality.SDR -> "SDR"
    SearchQuality.DVD -> "DVD"
    else -> quality.name
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, star: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(CsColors.Badge)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (star) Icon(Icons.Default.Star, null, tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(12.dp))
    }
}

@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    badge: String? = null,
    score: String? = null,
    subtitle: String? = null,
    horizontal: Boolean = false,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val settings = Store.settings
    val width = if (horizontal) posterWidth * 1.45f else posterWidth
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(width)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (horizontal) 16f / 9f else 2f / 3f)
                .scale(if (hovered) 1.04f else 1f)
                .clip(shape)
                .background(CsColors.Surface)
                .border(2.dp, if (hovered) Color.White else Color.Transparent, shape)
        ) {
            // Landscape cards are mostly channel logos: show them whole.
            RemoteImage(posterUrl, Modifier.fillMaxSize(), headers, if (horizontal) ContentScale.Fit else ContentScale.Crop) {
                Box(Modifier.fillMaxSize().background(CsColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Text(
                        title, color = CsColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null && settings.showQualityBadge) Badge(badge, Modifier.align(Alignment.TopStart).padding(7.dp))
            if (score != null && settings.showScoreBadge) Badge(score, Modifier.align(Alignment.TopEnd).padding(7.dp), star = true)
            if (!settings.titleBelowPoster) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(8.dp)
                ) {
                    Text(
                        title, fontSize = 14.sp, color = Color.White, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = CsColors.Primary,
                    trackColor = Color.Black.copy(alpha = 0.6f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        if (settings.titleBelowPoster) {
            Text(
                title,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
            )
        }
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = CsColors.TextMuted, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SearchResponseCard(item: SearchResponse, horizontal: Boolean = false, onOpen: (SearchResponse) -> Unit) {
    val dub = (item as? AnimeSearchResponse)?.dubStatus?.takeIf { Store.settings.showDubSubBadge }?.let { set ->
        listOfNotNull(if (DubStatus.Dubbed in set) "VF" else null, if (DubStatus.Subbed in set) "VOSTFR" else null)
            .joinToString("/").ifBlank { null }
    }
    PosterCard(
        title = item.name,
        posterUrl = item.posterUrl,
        headers = item.posterHeaders,
        badge = dub ?: qualityLabel(item.quality),
        score = item.score?.let { "%.1f".format(it.toDouble(10)).removeSuffix(",0").removeSuffix(".0") },
        horizontal = horizontal,
        onClick = { onOpen(item) },
    )
}

@Composable
fun PosterRow(
    title: String,
    items: List<SearchResponse>,
    horizontal: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    onOpen: (SearchResponse) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (onSeeAll != null) {
                IconButton(onClick = onSeeAll) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Voir tout", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.apiName + it.url }) { SearchResponseCard(it, horizontal, onOpen) }
        }
    }
}

@Composable
fun LoadingBox(text: String? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(36.dp))
        if (text != null) Text(text, color = CsColors.TextMuted, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun MessageBox(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Text(
                message, color = CsColors.TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp), maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** 754.2 -> "12:34", 3723.0 -> "1:02:03". */
fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f Go".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f Mo".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f Ko".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes o"
}
