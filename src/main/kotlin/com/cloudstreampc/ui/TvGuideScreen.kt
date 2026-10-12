package com.cloudstreampc.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.PlayArrow
import com.cloudstreampc.player.PlayQueue
import com.cloudstreampc.plugins.PluginManager
import com.cloudstreampc.providers.IptvProvider
import com.cloudstreampc.providers.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MinuteWidth = 4.dp
private val RowHeight = 68.dp
private val ChannelWidth = 220.dp
private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW_HOURS = 14

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)
private fun minutesWidth(ms: Long): Dp = MinuteWidth * (ms / 60_000f)

/** Programme grid of the IPTV accounts: channels in rows, time in columns, like a TV box guide. */
@Composable
fun TvGuideScreen() {
    val nav = LocalNavigator.current
    val providers = remember(PluginManager.revision) { PluginManager.providers.filterIsInstance<IptvProvider>() }
    var provider by remember(providers) { mutableStateOf(providers.firstOrNull()) }
    var channels by remember(provider) { mutableStateOf<List<IptvProvider.GuideChannel>?>(null) }
    var programmes by remember(provider) { mutableStateOf<Map<String, List<Programme>>>(emptyMap()) }
    var loadingGuide by remember(provider) { mutableStateOf(true) }
    var category by remember(provider) { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf<Pair<IptvProvider.GuideChannel, Programme>?>(null) }
    /** How far back the guide goes: a day when the provider keeps replays, else half an hour. */
    var pastMs by remember(provider) { mutableLongStateOf(HALF_HOUR) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(provider) {
        val current = provider ?: return@LaunchedEffect
        channels = runCatching { withContext(Dispatchers.IO) { current.channels() } }.getOrDefault(emptyList())
        programmes = runCatching { withContext(Dispatchers.IO) { current.loadGuide() } }.getOrDefault(emptyMap())
        if (runCatching { withContext(Dispatchers.IO) { current.hasReplay() } }.getOrDefault(false)) pastMs = 24 * 3600_000L
        loadingGuide = false
    }

    val windowStart = remember(pastMs) { now - now % HALF_HOUR - pastMs }
    val windowEnd = remember(pastMs) { now - now % HALF_HOUR - HALF_HOUR + WINDOW_HOURS * 3600_000L }
    val timeline = rememberScrollState()
    val density = LocalDensity.current
    fun scrollToNow() = scope.launch {
        timeline.animateScrollTo(with(density) { minutesWidth(System.currentTimeMillis() - windowStart - 15 * 60_000L).toPx() }.toInt().coerceAtLeast(0))
    }
    fun play(channel: IptvProvider.GuideChannel) {
        val api = provider ?: return
        scope.launch {
            val response = runCatching { withContext(Dispatchers.IO) { api.load(channel.url) } }.getOrNull()
            if (response == null || !PlayQueue.play(api, response, autoPlay = true)) nav.toast("Impossible d'ouvrir ${channel.name}")
        }
    }

    fun replay(channel: IptvProvider.GuideChannel, programme: Programme) {
        val api = provider ?: return
        scope.launch {
            val request = runCatching {
                withContext(Dispatchers.IO) {
                    val response = api.load(channel.url) ?: return@withContext null
                    val data = api.replayData(channel.url, programme.start, programme.stop) ?: return@withContext null
                    com.cloudstreampc.player.PlayRequest(
                        api, response, listOf(com.cloudstreampc.player.Playable(data, programme.title, channel.name)), 0, autoPlay = true,
                    )
                }
            }.getOrNull()
            if (request == null) nav.toast("Impossible d'ouvrir ${channel.name}") else com.cloudstreampc.player.PlaybackManager.request(request)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Guide TV", onBack = { nav.back() }) {
            TextButton(onClick = { scrollToNow() }) { Text("Maintenant") }
        }
        if (provider == null) {
            MessageBox("Aucun compte IPTV", "Ajoutez un compte Xtream ou une playlist M3U dans Paramètres > Comptes et Sécurité.")
            return@Column
        }
        val list = channels
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val categories = remember(list) { list.map { it.category }.distinct() }
        LazyRow(
            Modifier.padding(start = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (providers.size > 1) {
                items(providers, key = { "account:" + it.name }) { p ->
                    FilterChip(p == provider, { provider = p }, { Text(p.account.name) })
                }
                item { Spacer(Modifier.width(16.dp)) }
            }
            item { FilterChip(category == null, { category = null }, { Text("Toutes les chaînes") }) }
            items(categories, key = { "cat:$it" }) { name -> FilterChip(category == name, { category = name }, { Text(name) }) }
        }
        if (loadingGuide) {
            Text("Chargement du guide des programmes…", color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        } else if (programmes.isEmpty()) {
            Text(
                "Ce compte ne fournit pas de guide des programmes (XMLTV).",
                color = CsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
            )
        }

        LaunchedEffect(windowStart) { scrollToNow() }
        TimeHeader(windowStart, windowEnd, now, timeline)
        val shown = remember(list, category) { list.filter { category == null || it.category == category } }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.url }) { channel ->
                val entries = channel.epgId?.lowercase()?.let { programmes[it] }.orEmpty()
                    .filter { it.stop > windowStart && it.start < windowEnd }
                GuideRow(channel, entries, windowStart, windowEnd, now, timeline, onChannel = { play(channel) }) { programme ->
                    selected = channel to programme
                }
            }
        }
    }

    selected?.let { (channel, programme) ->
        val onAir = programme.isOn(now)
        var replayable by remember(programme) { mutableStateOf(false) }
        LaunchedEffect(programme) {
            if (programme.stop <= now) replayable = runCatching { provider?.canReplay(channel.url, programme.start) == true }.getOrDefault(false)
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Column {
                    Text(programme.title, fontWeight = FontWeight.Bold)
                    Text("${channel.name} · ${hhmm(programme.start)} – ${hhmm(programme.stop)}", fontSize = 13.sp, color = CsColors.TextMuted)
                }
            },
            text = { Text(programme.description ?: "Pas de résumé.", color = CsColors.TextMuted) },
            confirmButton = {
                if (replayable) {
                    Button(onClick = { selected = null; replay(channel, programme) }) {
                        Icon(androidx.compose.material.icons.Icons.Default.PlayArrow, null)
                        Text("  Revoir")
                    }
                }
                if (onAir) {
                    Button(onClick = { selected = null; play(channel) }) {
                        Icon(androidx.compose.material.icons.Icons.Default.PlayArrow, null)
                        Text("  Regarder")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun TimeHeader(windowStart: Long, windowEnd: Long, now: Long, timeline: ScrollState) {
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        Spacer(Modifier.width(ChannelWidth))
        Box(Modifier.weight(1f).horizontalScroll(timeline)) {
            Box(Modifier.width(minutesWidth(windowEnd - windowStart)).fillMaxHeight()) {
                var slot = windowStart
                while (slot < windowEnd) {
                    Text(
                        hhmm(slot), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CsColors.TextMuted,
                        modifier = Modifier.offset(x = minutesWidth(slot - windowStart) + 8.dp, y = 6.dp),
                    )
                    slot += HALF_HOUR
                }
                NowMarker(now - windowStart, label = true)
            }
        }
    }
}

@Composable
private fun NowMarker(offsetMs: Long, label: Boolean = false) {
    if (offsetMs < 0) return
    Box(Modifier.offset(x = minutesWidth(offsetMs) - 1.dp).width(2.dp).fillMaxHeight().background(CsColors.Error))
    if (label) {
        Box(Modifier.offset(x = minutesWidth(offsetMs) - 5.dp, y = 22.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(CsColors.Error))
    }
}

@Composable
private fun GuideRow(
    channel: IptvProvider.GuideChannel,
    entries: List<Programme>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    timeline: ScrollState,
    onChannel: () -> Unit,
    onProgramme: (Programme) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(ChannelWidth).fillMaxHeight().padding(start = 16.dp, end = 8.dp)
                .clip(RoundedCornerShape(8.dp)).background(CsColors.Surface).clickable(onClick = onChannel)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 52.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                RemoteImage(channel.logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, placeholder = {})
            }
            Spacer(Modifier.width(10.dp))
            Text(channel.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(timeline)) {
            Box(Modifier.width(minutesWidth(windowEnd - windowStart)).fillMaxHeight()) {
                if (entries.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(6.dp)).background(CsColors.Surface.copy(alpha = 0.5f))
                            .clickable(onClick = onChannel).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text("Programme non disponible", fontSize = 12.sp, color = CsColors.TextMuted) }
                }
                entries.forEach { programme -> ProgrammeCell(programme, windowStart, now, timeline) { onProgramme(programme) } }
                NowMarker(now - windowStart)
            }
        }
    }
}

@Composable
private fun ProgrammeCell(programme: Programme, windowStart: Long, now: Long, timeline: ScrollState, onClick: () -> Unit) {
    val start = maxOf(programme.start, windowStart)
    val onAir = programme.isOn(now)
    val past = programme.stop <= now
    val density = LocalDensity.current
    val cellStart = with(density) { minutesWidth(start - windowStart).toPx() }
    val cellWidth = with(density) { minutesWidth(programme.stop - start).toPx() }
    val minText = with(density) { 90.dp.toPx() }
    Box(
        Modifier.offset(x = minutesWidth(start - windowStart)).width(minutesWidth(programme.stop - start)).fillMaxHeight()
            .padding(2.dp).clip(RoundedCornerShape(6.dp))
            .background(if (onAir) CsColors.SurfaceHigh else CsColors.Surface)
            .clickable(onClick = onClick),
    ) {
        // A programme that began before the visible part keeps its title at the left edge.
        Column(
            Modifier
                .offset {
                    val shift = (timeline.value - cellStart).coerceIn(0f, (cellWidth - minText).coerceAtLeast(0f))
                    androidx.compose.ui.unit.IntOffset(shift.toInt(), 0)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                programme.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (past) CsColors.TextMuted else Color.White,
            )
            Text("${hhmm(programme.start)} – ${hhmm(programme.stop)}", fontSize = 11.sp, color = CsColors.TextMuted, maxLines = 1)
        }
        if (onAir) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(programme.progress(now)).fillMaxHeight().background(CsColors.Primary))
            }
        }
    }
}
