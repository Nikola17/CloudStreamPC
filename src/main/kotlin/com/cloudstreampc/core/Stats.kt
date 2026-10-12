package com.cloudstreampc.core

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Time actually spent watching one title on one day (seeks and pauses don't count). */
data class WatchEvent(
    val day: String = "",
    val apiName: String = "",
    val url: String = "",
    val name: String = "",
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val seconds: Long = 0,
    val episode: String? = null,
)

/** Totals for a year (or all years), shown in "Statistiques" and "Ton année". */
data class YearStats(
    val year: Int?,
    val seconds: Long,
    val movies: Int,
    val episodes: Int,
    val titles: Int,
    val liveSeconds: Long,
    val topTitles: List<Pair<String, Long>>,
    val topGenres: List<Pair<String, Long>>,
    /** Seconds watched per month (1..12). */
    val months: Map<Int, Long>,
    val activeDays: Int,
    val longestStreak: Int,
    val finishedSeries: Int,
)

/** Watching statistics of the current profile, kept on this PC only. */
object Stats {
    private fun file(profile: String = Store.settings.currentProfile) = File(AppDirs.root, "stats-$profile.json")

    @Volatile private var cache: Pair<String, MutableList<WatchEvent>>? = null

    @Synchronized
    fun events(): List<WatchEvent> = loaded().toList()

    @Synchronized
    private fun loaded(): MutableList<WatchEvent> {
        val profile = Store.settings.currentProfile
        cache?.takeIf { it.first == profile }?.let { return it.second }
        val list = readJsonFile(file(profile)) { emptyList<WatchEvent>() }.toMutableList()
        cache = profile to list
        return list
    }

    /** Adds [seconds] of watching (under 20 s is not counted: a quick look is not watching). */
    @Synchronized
    fun record(response: LoadResponse, episode: String?, seconds: Double, now: Long = System.currentTimeMillis()) {
        if (seconds < 20) return
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val list = loaded()
        val index = list.indexOfFirst { it.day == day && it.apiName == response.apiName && it.url == response.url && it.episode == episode }
        if (index >= 0) {
            list[index] = list[index].copy(seconds = list[index].seconds + seconds.toLong())
        } else {
            list += WatchEvent(
                day, response.apiName, response.url, response.name, response.type.name,
                response.tags.orEmpty().take(5), seconds.toLong(), episode,
            )
        }
        runCatching { writeJsonFile(file(), list) }
    }

    fun years(events: List<WatchEvent> = events()): List<Int> =
        events.mapNotNull { it.day.take(4).toIntOrNull() }.distinct().sortedDescending()

    fun of(year: Int?, events: List<WatchEvent> = events(), library: UserLibrary = Store.library): YearStats {
        val chosen = events.filter { year == null || it.day.startsWith("$year-") }
        val live = { e: WatchEvent -> e.type == TvType.Live.name }
        val films = setOf(TvType.Movie.name, TvType.AnimeMovie.name, TvType.Documentary.name)
        val days = chosen.map { it.day }.distinct().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (d in days) {
            run = if (previous != null && previous.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            previous = d
        }
        val finished = library.statuses.values.count { entry ->
            entry.status == WatchStatus.COMPLETED && entry.item.type != TvType.Movie.name &&
                (year == null || Instant.ofEpochMilli(entry.updatedAt).atZone(ZoneId.systemDefault()).year == year)
        }
        return YearStats(
            year = year,
            seconds = chosen.sumOf { it.seconds },
            movies = chosen.filter { it.type in films }.map { it.apiName + it.url }.distinct().size,
            episodes = chosen.filter { it.episode != null && !live(it) && it.type !in films }.map { it.apiName + it.url + it.episode }.distinct().size,
            titles = chosen.map { it.apiName + it.url }.distinct().size,
            liveSeconds = chosen.filter(live).sumOf { it.seconds },
            topTitles = chosen.filter { !live(it) }.groupBy { it.name }.mapValues { (_, v) -> v.sumOf { it.seconds } }
                .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topGenres = chosen.flatMap { e -> e.genres.map { it to e.seconds } }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
            months = chosen.groupBy { it.day.substring(5, 7).toInt() }.mapValues { (_, v) -> v.sumOf { it.seconds } },
            activeDays = days.size,
            longestStreak = longest,
            finishedSeries = finished,
        )
    }
}
