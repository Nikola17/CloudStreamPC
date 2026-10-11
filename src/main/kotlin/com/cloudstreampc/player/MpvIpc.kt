package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.mapper
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * Reads the playback position from mpv through its JSON IPC
 * (a named pipe on Windows, a unix socket elsewhere).
 */
object MpvIpc {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun newServerPath(): String {
        val id = UUID.randomUUID().toString().take(8)
        return if (isWindows) {
            "\\\\.\\pipe\\cloudstreampc-$id"
        } else {
            File(System.getProperty("java.io.tmpdir"), "cspc-$id.sock").path
        }
    }

    /**
     * Blocks until mpv exits, calling [onUpdate] with the latest position and duration (seconds).
     * Returns quietly if the IPC cannot be reached: playback simply isn't tracked then.
     */
    fun observe(path: String, process: Process, onUpdate: (position: Double?, duration: Double?) -> Unit) {
        val commands = listOf(
            """{"command":["observe_property",1,"time-pos"]}""",
            """{"command":["observe_property",2,"duration"]}""",
        ).joinToString("\n", postfix = "\n").toByteArray()

        // mpv creates the pipe/socket a moment after starting.
        var input: InputStream? = null
        var closer: AutoCloseable? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (input == null && process.isAlive && System.currentTimeMillis() < deadline) {
            try {
                if (isWindows) {
                    val pipe = RandomAccessFile(path, "rw")
                    // Write everything before reading: a synchronous pipe can't read and write at once.
                    pipe.write(commands)
                    input = Channels.newInputStream(pipe.channel)
                    closer = pipe
                } else {
                    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                    channel.connect(UnixDomainSocketAddress.of(path))
                    channel.write(ByteBuffer.wrap(commands))
                    input = Channels.newInputStream(channel)
                    closer = channel
                }
            } catch (_: Exception) {
                Thread.sleep(250)
            }
        }
        if (input == null) {
            Logger.info("IPC mpv indisponible, la position ne sera pas enregistrée")
            return
        }

        var position: Double? = null
        var duration: Double? = null
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val node = runCatching { mapper.readTree(line) }.getOrNull() ?: continue
                    if (node.path("event").asText() != "property-change") continue
                    val value = node.get("data")?.takeIf { it.isNumber }?.asDouble()
                    when (node.path("name").asText()) {
                        "time-pos" -> position = value ?: position
                        "duration" -> duration = value ?: duration
                        else -> continue
                    }
                    onUpdate(position, duration)
                }
            }
        } catch (_: Exception) {
            // mpv closed the connection when quitting.
        } finally {
            runCatching { closer?.close() }
            if (!isWindows) File(path).delete()
        }
    }
}
