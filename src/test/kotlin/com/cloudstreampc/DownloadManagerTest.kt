package com.cloudstreampc

import com.cloudstreampc.download.DownloadManager
import com.cloudstreampc.download.DownloadStatus
import com.cloudstreampc.player.PlayRequest
import com.cloudstreampc.player.Playable
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Downloads an AES-128 encrypted HLS stream made with ffmpeg (skipped without ffmpeg). */
class DownloadManagerTest {
    private object FakeApi : MainAPI() {
        override var name = "Test"
        override var mainUrl = "http://test"
    }

    private fun run(vararg command: String) =
        ProcessBuilder(*command).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()

    @Test
    fun downloadsEncryptedHls() = runBlocking {
        if (listOf("/usr/bin/ffmpeg", "/usr/bin/ffprobe").any { !File(it).exists() }) return@runBlocking
        val dir = Files.createTempDirectory("hls").toFile()
        File(dir, "enc.key").writeBytes(ByteArray(16) { it.toByte() })
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val bytes = File(dir, ex.requestURI.path.removePrefix("/")).readBytes()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        File(dir, "keyinfo").writeText("$base/enc.key\n${File(dir, "enc.key").path}\n")
        assertEquals(0, run(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=6:size=160x90:rate=10",
            "-c:v", "libx264", "-hls_time", "1", "-hls_playlist_type", "vod",
            "-hls_key_info_file", File(dir, "keyinfo").path, File(dir, "video.m3u8").path,
        ))

        val response: LoadResponse = FakeApi.newMovieLoadResponse("Film test", "$base/page", TvType.Movie, "data")
        val request = PlayRequest(FakeApi, response, listOf(Playable("data", null, "Film test")), 0)
        val link = newExtractorLink("Test", "HLS", "$base/video.m3u8", ExtractorLinkType.M3U8)
        DownloadManager.enqueue(request, link)
        val item = withTimeout(60_000) {
            while (DownloadManager.items.first().status !in setOf(DownloadStatus.DONE, DownloadStatus.FAILED)) delay(200)
            DownloadManager.items.first()
        }
        server.stop(0)
        println("Statut ${item.status} ${item.error ?: ""} ${item.doneSegments}/${item.totalSegments} -> ${item.filePath}")
        assertEquals(DownloadStatus.DONE, item.status)

        val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", item.filePath)
            .start().inputStream.bufferedReader().readText().trim()
        println("Durée du fichier : $probe s")
        assertTrue((probe.toDoubleOrNull() ?: 0.0) > 5.0)
        DownloadManager.delete(item.id)
    }
}
