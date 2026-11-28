package com.moncloudi

import com.moncloudi.core.mapper
import com.moncloudi.player.ExternalPlayer
import com.moncloudi.providers.Enrichment
import com.moncloudi.providers.TopRecentRanking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonCloudiFeaturesTest {

    @Test
    fun topRecentRankingBlendsRecencyPopularityAndRatingAndFiltersJunk() {
        val today = LocalDate.of(2026, 11, 22)
        val candidates = listOf(
            // Future unreleased movie -> MUST be excluded
            TopRecentRanking.Item("tt_future", "Unreleased Hype", null, 2027, 9.2, LocalDate.of(2027, 3, 1), listOf("Sci-Fi"), 800.0, 0.0, "movie"),
            // Very recent but terrible rating (4.0) -> MUST be excluded
            TopRecentRanking.Item("tt_bad", "Chroniques Ratées", null, 2026, 4.0, LocalDate.of(2026, 11, 10), listOf("Drama"), 300.0, 0.0, "movie"),
            // Old classic (2015) -> MUST be excluded from recent Top 10
            TopRecentRanking.Item("tt_old", "Classic 2015", null, 2015, 8.8, LocalDate.of(2015, 6, 1), listOf("Action"), 400.0, 0.0, "movie"),
            // Out of cinemas (4 months), very popular, great rating -> #1!
            TopRecentRanking.Item("tt_hit", "Great Recent Hit", null, 2026, 8.4, LocalDate.of(2026, 7, 15), listOf("Action", "Drama"), 480.0, 0.0, "movie"),
            // Older (10 months ago), decent rating, lower popularity -> lower rank
            TopRecentRanking.Item("tt_older", "Decent Earlier Film", null, 2026, 6.8, LocalDate.of(2026, 1, 20), listOf("Comedy"), 30.0, 0.0, "movie"),
            // Recent (3 and a half months), solid rating, solid popularity -> #2
            TopRecentRanking.Item("tt_good", "Fresh Solid Thriller", null, 2026, 7.6, LocalDate.of(2026, 8, 5), listOf("Thriller"), 160.0, 0.0, "movie"),
            // Released 3 weeks ago: still in cinemas -> MUST be excluded
            TopRecentRanking.Item("tt_cinema", "Au cinéma", null, 2026, 8.9, LocalDate.of(2026, 11, 1), listOf("Thriller"), 700.0, 0.0, "movie"),
        )

        val ranked = TopRecentRanking.rankCandidates(candidates, today)
        val ids = ranked.map { it.id }

        assertFalse("tt_future" in ids, "Unreleased future movies must not appear in Top 10")
        assertFalse("tt_bad" in ids, "Low-rated movies (4.0) must not appear in Top 10")
        assertFalse("tt_old" in ids, "Old movies must not appear in recent Top 10")
        assertFalse("tt_cinema" in ids, "Movies still in cinemas must not appear in Top 10")
        assertEquals(listOf("tt_hit", "tt_good", "tt_older"), ids)
    }

    @Test
    fun similarRankingPrioritizesGenreDirectorAndCastMatch() {
        val json = """
            [
              {"id":"tt_self","name":"Inception","genres":["Action","Sci-Fi","Thriller"],"director":["Christopher Nolan"],"cast":["Leonardo DiCaprio"],"releaseInfo":"2010","imdbRating":"8.8"},
              {"id":"tt_best","name":"Interstellar","genres":["Adventure","Drama","Sci-Fi"],"director":["Christopher Nolan"],"cast":["Matthew McConaughey"],"releaseInfo":"2014","imdbRating":"8.7"},
              {"id":"tt_genre","name":"Shutter Island","genres":["Mystery","Thriller"],"director":["Martin Scorsese"],"cast":["Leonardo DiCaprio"],"releaseInfo":"2010","imdbRating":"8.2"},
              {"id":"tt_multi","name":"Tenet","genres":["Action","Sci-Fi","Thriller"],"director":["Christopher Nolan"],"cast":["John David Washington"],"releaseInfo":"2020","imdbRating":"7.3"},
              {"id":"tt_unrelated","name":"Random Romcom","genres":["Romance","Comedy"],"director":["Someone Else"],"cast":["Actor X"],"releaseInfo":"1995","imdbRating":"6.0"}
            ]
        """.trimIndent()
        val nodes = mapper.readTree(json).toList()

        val ranked = Enrichment.rankSimilarNodes(
            candidates = nodes,
            targetId = "tt_self",
            targetNormalizedTitle = "inception",
            targetGenres = setOf("action", "sci-fi", "thriller"),
            targetDirectors = setOf("christopher nolan"),
            targetCast = setOf("leonardo dicaprio"),
            targetYear = 2010,
        )

        assertFalse(ranked.any { it.title == "Inception" }, "Current title must be excluded")
        assertEquals("Tenet", ranked.first().title, "Exact genre + director match should rank #1")
        assertEquals("Random Romcom", ranked.last().title, "Unrelated film should rank last")
    }

    @Test
    fun playerPerformanceVodAndErgonomicControlsConfigured() {
        val perf = ExternalPlayer.performanceArgs()
        assertTrue("--hwdec=auto-safe" in perf)
        assertTrue("--vo=gpu-next,gpu" in perf)
        val perfEmbedded = ExternalPlayer.performanceArgs(embedded = true)
        assertTrue("--hwdec=auto-safe" in perfEmbedded)
        if ("win" in System.getProperty("os.name").lowercase()) {
            assertTrue("--vo=gpu" in perfEmbedded)
            assertTrue("--d3d11-flip=no" in perfEmbedded)
        }

        val vod = ExternalPlayer.vodArgs()
        assertTrue("--cache=yes" in vod)
        assertTrue("--demuxer-readahead-secs=30" in vod)
        assertTrue("--stream-lavf-o-append=reconnect=1" in vod)

        val iface = ExternalPlayer.interfaceArgs(hasNext = true, live = false, embedded = false, hasEpisodes = true, hasSources = true)
        val controlsOpt = iface.firstOrNull { it.startsWith("--script-opts-append=uosc-controls=") }
        assertTrue(controlsOpt != null, "uosc-controls must be generated when scriptsDir is present")
        val controls = controlsOpt.substringAfter("uosc-controls=").split(",")
        // Back button first, then gap, play-pause, -10s, +10s
        assertTrue(controls[0].startsWith("command:arrow_back:"), "Back button must be first control")
        assertEquals("play-pause", controls[2])
        assertTrue(controls[3].startsWith("command:replay_10:"))
        assertTrue(controls[4].startsWith("command:forward_10:"))
        assertTrue(controls.any { it.contains("moncloudi sources") }, "Sources (VF/VOSTFR) button must be present")
        assertTrue(controls.any { it.contains("moncloudi episodes") }, "Episodes button must be present")
        assertTrue(controls.any { it == "<has_audio>audio" }, "Audio button must be shown whenever audio is present")
        assertTrue(controls.any { it.contains("moncloudi aspect") }, "Aspect ratio button must be present")

        val vodBuf = ExternalPlayer.vodBufferCommands()
        assertTrue(vodBuf.any { it == listOf("set_property", "cache-pause-wait", 1.5) })
        assertTrue(vodBuf.any { it == listOf("set_property", "demuxer-readahead-secs", 30) })
    }

    @Test
    fun playerControllerParsesMpvTrackListAndSubtitleLanguages() {
        val json = """
            [
              {"id":1,"type":"video","codec":"h264","selected":true},
              {"id":1,"type":"audio","title":"Français VF","lang":"fre","codec":"aac","audio-channels":6,"selected":true},
              {"id":2,"type":"audio","title":"English","lang":"eng","codec":"ac3","audio-channels":2,"selected":false},
              {"id":1,"type":"sub","title":"OpenSubtitles · Français 1","lang":"fre","codec":"subrip","external":true,"selected":false,"forced":false},
              {"id":2,"type":"sub","lang":"fre","codec":"ass","external":false,"selected":true,"forced":true}
            ]
        """.trimIndent()
        val tracks = com.moncloudi.player.PlayerController.parseTracks(mapper.readTree(json))
        assertEquals(5, tracks.size)
        val audio = tracks.filter { it.type == "audio" }
        assertEquals(2, audio.size)
        assertTrue(audio[0].label.contains("5.1"))
        val subs = tracks.filter { it.type == "sub" }
        assertEquals(2, subs.size)
        assertTrue(subs[0].label.contains("externe"))
        assertTrue(subs[1].label.contains("forcé"))
        assertEquals("fre", com.moncloudi.player.OnlineSubtitles.inferLangCode("Français (VF)"))
        assertEquals("eng", com.moncloudi.player.OnlineSubtitles.inferLangCode("English"))
        assertEquals(listOf("fre", "eng"), com.moncloudi.player.OnlineSubtitles.wantedLanguages("fr", includeEnglishFallback = true))

        // Verify moncloudi.lua never steals ENTER with add_forced_key_binding (which broke uosc menus)
        val lua = java.io.File("app-resources/common/mpv/scripts/moncloudi.lua").readText()
        assertFalse(lua.contains("add_forced_key_binding('ENTER'"), "moncloudi.lua must not force-bind ENTER over uosc menus")
        assertTrue(lua.contains("user-data/uosc/menu/type"), "moncloudi.lua must observe uosc menu visibility")
    }

    @Test
    fun playerControllerDrivesLiveMpvAudioSubtitlesAndAspect() {
        val mpv = System.getProperty("test.mpv")?.let { java.io.File(it) }?.takeIf { it.isFile } ?: return
        val ipc = com.moncloudi.player.MpvIpc.newServerPath()
        val subFile = java.io.File.createTempFile("moncloudi-sub-", ".srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:05,000\nBonjour MonCloudi\n")
            deleteOnExit()
        }
        val proc = ProcessBuilder(
            mpv.absolutePath, "--no-config", "--vo=null", "--ao=null",
            "--input-ipc-server=$ipc",
            "--", "av://lavfi:testsrc=size=320x240:rate=25:duration=15",
        ).redirectErrorStream(true).start()
        try {
            com.moncloudi.player.PlayerController.attach(proc, ipc)
            val watcher = Thread {
                com.moncloudi.player.MpvIpc.watch(ipc, proc, com.moncloudi.player.PlayerController.observedProperties) { name, value ->
                    com.moncloudi.player.PlayerController.onProperty(proc, name, value)
                }
            }.apply { isDaemon = true; start() }

            // Wait for mpv IPC readiness
            val readyUntil = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < readyUntil && com.moncloudi.player.MpvIpc.get(ipc, "time-pos")["time-pos"] == null) {
                Thread.sleep(150)
            }
            com.moncloudi.player.PlayerController.addLocalSubtitle(subFile)
            com.moncloudi.player.PlayerController.changeSubDelay(0.5)
            com.moncloudi.player.PlayerController.changeSpeed(1.25)
            com.moncloudi.player.PlayerController.setAspect(com.moncloudi.player.VideoAspectMode.FILL)

            val checkUntil = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < checkUntil &&
                (com.moncloudi.player.PlayerController.subtitleTracks.isEmpty() ||
                    com.moncloudi.player.PlayerController.speed != 1.25 ||
                    com.moncloudi.player.PlayerController.subDelay != 0.5)
            ) {
                Thread.sleep(150)
            }
            assertTrue(com.moncloudi.player.PlayerController.subtitleTracks.isNotEmpty(), "External subtitle must appear in PlayerController.subtitleTracks")
            assertEquals(0.5, com.moncloudi.player.PlayerController.subDelay, "Subtitle delay must sync via IPC")
            assertEquals(1.25, com.moncloudi.player.PlayerController.speed, "Speed must sync via IPC")
            assertEquals(com.moncloudi.player.VideoAspectMode.FILL, com.moncloudi.player.PlayerController.aspectMode, "Aspect mode must sync via IPC")

            // Disable subtitles and verify sid becomes null
            com.moncloudi.player.PlayerController.selectSubtitle(null)
            Thread.sleep(400)
            assertEquals(null, com.moncloudi.player.PlayerController.activeSubId, "Disabling subtitles must clear activeSubId")

            com.moncloudi.player.MpvIpc.send(ipc, listOf("quit", 0))
            watcher.join(2000)
        } finally {
            com.moncloudi.player.PlayerController.detach(proc)
            if (proc.isAlive) proc.destroyForcibly()
            subFile.delete()
        }
    }
}
