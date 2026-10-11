package com.cloudstreampc

import com.cloudstreampc.player.MpvIpc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Needs mpv (system, or `-Dtest.mpv`); skipped otherwise. */
class MpvIpcTest {
    @Test
    fun readsPositionAndDuration() {
        val mpv = mpvExecutable() ?: return
        val path = MpvIpc.newServerPath()
        val process = ProcessBuilder(
            mpv, "--vo=null", "--ao=null", "--no-config", "--input-ipc-server=$path",
            "av://lavfi:testsrc=duration=4:rate=10",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        var lastPosition: Double? = null
        var lastDuration: Double? = null
        MpvIpc.observe(path, process) { position, duration ->
            lastPosition = position
            lastDuration = duration
        }
        process.waitFor()
        println("position=$lastPosition duration=$lastDuration")
        assertNotNull(lastDuration)
        assertTrue(lastPosition!! > 3.0)
    }

    /** Our player script answers through the IPC, and commands sent on a second connection run. */
    @Test
    fun talksToThePlayerScript() {
        val mpv = mpvExecutable() ?: return
        val script = File("app-resources/common/mpv/scripts/cloudstreampc.lua").absoluteFile
        val path = MpvIpc.newServerPath()
        val process = ProcessBuilder(
            mpv, "--vo=null", "--ao=null", "--no-config", "--input-ipc-server=$path", "--keep-open=yes",
            "--script=${script.path}", "--script-opts-append=cloudstreampc-has_next=yes",
            "av://lavfi:testsrc=duration=30:rate=10",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val observer = Thread {
            MpvIpc.observe(path, process, onEvent = { name, args ->
                if (name == "client-message") messages += args.joinToString(" ")
            }) { _, _ -> }
        }.apply { start() }

        // "Épisode suivant" asked from the player (as the button or the N key would).
        assertTrue(MpvIpc.toScript(path, "next"))
        val deadline = System.currentTimeMillis() + 10_000
        while ("cspc-next" !in messages && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("cspc-next" in messages, "messages: $messages")

        assertTrue(MpvIpc.send(path, listOf("set_property", "pause", false), listOf("quit", 0)))
        process.waitFor()
        observer.join(5_000)
    }

    /** `-Dtest.mpv=...` (the Windows build passes the bundled mpv.exe), or the system mpv. */
    private fun mpvExecutable() = (listOfNotNull(System.getProperty("test.mpv")) + listOf("/usr/bin/mpv", "/usr/local/bin/mpv"))
        .map(::File).firstOrNull { it.isFile }?.absolutePath
}
