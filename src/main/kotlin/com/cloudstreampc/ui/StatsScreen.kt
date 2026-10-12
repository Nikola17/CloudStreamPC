package com.cloudstreampc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Stats
import com.cloudstreampc.core.YearStats

private val monthNames = listOf("Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin", "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc.")

/** "Statistiques": time watched, titles, genres, and the "Ton année" summary of a year. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen() {
    val nav = LocalNavigator.current
    val events = remember { Stats.events() }
    val years = remember(events) { Stats.years(events) }
    var year by remember { mutableStateOf(years.firstOrNull()) }
    val stats = remember(year, events) { Stats.of(year, events) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Statistiques", onBack = { nav.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp).padding(bottom = 48.dp)) {
            if (events.isEmpty()) {
                Text(
                    "Rien pour l'instant : le temps passé devant les vidéos sera compté ici (sur ce PC, pour ce profil).",
                    color = CsColors.TextMuted, modifier = Modifier.padding(top = 24.dp),
                )
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.forEach { y -> FilterChip(year == y, { year = y }, { Text(y.toString()) }) }
                FilterChip(year == null, { year = null }, { Text("Depuis le début") })
            }
            Spacer(Modifier.height(20.dp))
            YearCard(stats)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Titres les plus regardés", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Bars(stats.topTitles)
                }
                Column(Modifier.weight(1f)) {
                    Text("Genres", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    if (stats.topGenres.isEmpty()) Text("Les extensions n'ont pas indiqué de genres.", color = CsColors.TextMuted, fontSize = 13.sp)
                    Bars(stats.topGenres)
                }
            }
            if (stats.year != null) {
                Spacer(Modifier.height(24.dp))
                Text("Mois par mois", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Months(stats.months)
            }
        }
    }
}

fun hours(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    return if (h > 0) "$h h ${"%02d".format(m)}" else "$m min"
}

/** "Ton année": the big numbers, on a gradient card like the streaming services' yearly recaps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearCard(s: YearStats) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(CsColors.Primary, Color(0xFF7B2FF7), Color(0xFFE91E63))))
            .padding(28.dp),
    ) {
        Text(if (s.year != null) I18n.tr("Ton année {}").replace("{}", s.year.toString()) else "Depuis le début", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Big(hours(s.seconds), "devant l'écran")
            Big(s.titles.toString(), "titres")
            Big(s.movies.toString(), "films")
            Big(s.episodes.toString(), "épisodes")
            if (s.finishedSeries > 0) Big(s.finishedSeries.toString(), "séries terminées")
            if (s.liveSeconds > 0) Big(hours(s.liveSeconds), "de TV en direct")
            Big(s.activeDays.toString(), "jours de visionnage")
            if (s.longestStreak > 1) Big(s.longestStreak.toString(), "jours d'affilée au plus")
        }
        s.topTitles.firstOrNull()?.let { (name, seconds) ->
            Spacer(Modifier.height(18.dp))
            Text(I18n.tr("Ton titre n°1 : {}").replace("{}", name) + " · " + hours(seconds), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        s.topGenres.firstOrNull()?.let { (genre, _) ->
            Text(I18n.tr("Ton genre préféré : {}").replace("{}", genre), fontSize = 16.sp, color = Color.White.copy(alpha = 0.9f))
        }
        s.months.maxByOrNull { it.value }?.takeIf { s.year != null }?.let { (month, _) ->
            Text(I18n.tr("Ton mois le plus chargé : {}").replace("{}", I18n.tr(monthNames[month - 1])), fontSize = 16.sp, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column {
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun Bars(values: List<Pair<String, Long>>) {
    val max = values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { (name, seconds) ->
            Column {
                Row {
                    Text(text = name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(hours(seconds), color = CsColors.TextMuted, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(CsColors.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(seconds.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(CsColors.Primary))
                }
            }
        }
    }
}

@Composable
private fun Months(months: Map<Int, Long>) {
    val max = months.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        (1..12).forEach { m ->
            val value = months[m] ?: 0
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier.widthIn(max = 36.dp).fillMaxWidth().fillMaxHeight(0.82f * value / max + 0.01f)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (value > 0) CsColors.Primary else CsColors.SurfaceHigh),
                )
                Text(monthNames[m - 1], fontSize = 11.sp, color = CsColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
