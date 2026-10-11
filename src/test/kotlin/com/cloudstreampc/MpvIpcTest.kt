package com.cloudstreampc

import com.cloudstreampc.player.MpvIpc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Needs mpv installed; skipped otherwise. */
class MpvIpcTest {
    @Test
    fun readsPositionAndDuration() {
        val mpv = listOf("/usr/bin/mpv", "/usr/local/bin/mpv").map(::File).firstOrNull { it.isFile } ?: return
        val path = MpvIpc.newServerPath()
        val process = ProcessBuilder(
            mpv.path, "--vo=null", "--ao=null", "--no-config", "--input-ipc-server=$path",
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
}
