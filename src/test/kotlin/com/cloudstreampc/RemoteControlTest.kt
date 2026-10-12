package com.cloudstreampc

import com.cloudstreampc.core.mapper
import com.cloudstreampc.player.MpvIpc
import com.cloudstreampc.player.PlayerSurface
import com.cloudstreampc.player.RemoteControl
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteControlTest {
    private val port = RemoteControl.start()!!

    @AfterTest
    fun stop() = RemoteControl.stop()

    private fun call(path: String, method: String = "GET", key: String? = null): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        key?.let { connection.setRequestProperty("X-Remote-Key", it) }
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    @Test
    fun needsTheCodeOrTheKey() {
        val (pageCode, page) = call("/")
        assertEquals(200, pageCode)
        assertTrue("X-Remote-Key" in page)
        assertEquals(401, call("/api/state").first)
        assertEquals(401, call("/api/state", key = "faux").first)
        assertEquals(200, call("/api/state", key = RemoteControl.key).first)

        val wrong = if (RemoteControl.pin == "1234") "4321" else "1234"
        assertEquals(401, call("/api/pair?pin=$wrong", "POST").first)
        val (code, body) = call("/api/pair?pin=${RemoteControl.pin}", "POST")
        assertEquals(200, code)
        val key = mapper.readTree(body).path("key").asText()
        assertEquals("""{"playing":false}""", call("/api/state", key = key).second)
        // Nothing plays: the command can't be carried out.
        assertEquals(409, call("/api/cmd?c=toggle", "POST", key).first)

        // Five wrong codes in a row lock the pairing for a minute, even with the right code.
        repeat(5) { call("/api/pair?pin=$wrong", "POST") }
        assertEquals(429, call("/api/pair?pin=${RemoteControl.pin}", "POST").first)
    }

    @Test
    fun drivesMpv() {
        val mpv = (listOfNotNull(System.getProperty("test.mpv")) + listOf("/usr/bin/mpv", "/usr/local/bin/mpv"))
            .map(::File).firstOrNull { it.isFile } ?: return
        // A real file when ffmpeg is there (seeking is checked then), otherwise a picture made by mpv.
        val video = File(System.getProperty("java.io.tmpdir"), "cspc-remote-test.mp4")
        val file = video.isFile || runCatching {
            ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=10", "-t", "60", "-pix_fmt", "yuv420p", video.path)
                .inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
        val target = if (file) video.path else "av://lavfi:testsrc=size=320x180:rate=10:duration=60"
        val path = MpvIpc.newServerPath()
        val process = ProcessBuilder(
            mpv.path, "--vo=null", "--ao=null", "--no-config", "--idle=no", "--input-ipc-server=$path",
            "--force-media-title=Essai remote", target,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            PlayerSurface.follow(process, path)
            Thread.sleep(1500)
            val state = mapper.readTree(call("/api/state", key = RemoteControl.key).second)
            println("État : $state")
            assertEquals(true, state.path("playing").asBoolean())
            assertEquals("Essai remote", state.path("title").asText())
            assertEquals(false, state.path("paused").asBoolean())

            assertEquals(200, call("/api/cmd?c=toggle", "POST", RemoteControl.key).first)
            assertEquals(200, call("/api/cmd?c=forward", "POST", RemoteControl.key).first)
            Thread.sleep(400)
            val after = mapper.readTree(call("/api/state", key = RemoteControl.key).second)
            println("Après pause et +10 s : $after")
            assertEquals(true, after.path("paused").asBoolean())
            if (file) assertTrue(after.path("position").asDouble() >= 10.0)

            assertEquals(200, call("/api/cmd?c=stop", "POST", RemoteControl.key).first)
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            process.destroy()
        }
    }
}
