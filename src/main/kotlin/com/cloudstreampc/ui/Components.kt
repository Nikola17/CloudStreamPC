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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse

fun SearchResponse.yearOrNull(): Int? = when (this) {
    is MovieSearchResponse -> year
    is TvSeriesSearchResponse -> year
    is AnimeSearchResponse -> year
    else -> null
}

@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    badge: String? = null,
    subtitle: String? = null,
    horizontal: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val width = if (horizontal) 240.dp else 140.dp
    Column(
        modifier
            .width(width)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (horizontal) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, if (hovered) CsColors.Primary else Color.Transparent, RoundedCornerShape(10.dp))
        ) {
            // Landscape cards are mostly channel logos: show them whole.
            RemoteImage(posterUrl, Modifier.fillMaxSize(), headers, if (horizontal) ContentScale.Fit else ContentScale.Crop) {
                Box(Modifier.fillMaxSize().background(CsColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Text(
                        title, color = CsColors.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null) {
                Text(
                    badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CsColors.Primary)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (hovered) Color.White else Color(0xFFE0E0E0),
        )
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.sp, color = CsColors.TextMuted, maxLines = 1)
        }
    }
}

@Composable
fun SearchResponseCard(item: SearchResponse, horizontal: Boolean = false, onOpen: (SearchResponse) -> Unit) {
    PosterCard(
        title = item.name,
        posterUrl = item.posterUrl,
        headers = item.posterHeaders,
        badge = item.quality?.name?.let { qualityLabel(it) },
        subtitle = item.yearOrNull()?.toString(),
        horizontal = horizontal,
        onClick = { onOpen(item) },
    )
}

private fun qualityLabel(name: String) = when (name) {
    "HD", "BlueRay", "FourK", "UHD", "SDR", "HDR", "WebRip", "SD", "HdCam", "Cam", "CamRip", "Telecine",
    "Telesync", "DVD", "WorkPrint" -> name.replace("FourK", "4K").replace("BlueRay", "BluRay")
    else -> name
}

@Composable
fun PosterRow(
    title: String,
    items: List<SearchResponse>,
    horizontal: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    onOpen: (SearchResponse) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text("Voir tout") }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
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
