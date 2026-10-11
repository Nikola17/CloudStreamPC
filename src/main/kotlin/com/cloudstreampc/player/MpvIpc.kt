package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.compactJson
import com.cloudstreampc.core.mapper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * Talks to mpv through its JSON IPC (a named pipe on Windows, a unix socket elsewhere):
 * follows the playback position and the messages of our player script, and sends commands.
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

    private class Connection(val input: InputStream, val output: OutputStream, val closer: AutoCloseable)

    private fun connect(path: String): Connection = if (isWindows) {
        val pipe = RandomAccessFile(path, "rw")
        Connection(Channels.newInputStream(pipe.channel), Channels.newOutputStream(pipe.channel), pipe)
    } else {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        Connection(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel)
    }

    /** mpv creates the pipe/socket a moment after starting. */
    private fun connectWhenReady(path: String, process: Process?, timeoutMs: Long): Connection? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while ((process == null || process.isAlive) && System.currentTimeMillis() < deadline) {
            try {
                return connect(path)
            } catch (_: Exception) {
                Thread.sleep(250)
            }
        }
        return null
    }

    private fun commandJson(args: List<Any?>, requestId: Int? = null): String {
        val node = mapper.createObjectNode()
        node.set<com.fasterxml.jackson.databind.JsonNode>("command", mapper.valueToTree(args))
        if (requestId != null) node.put("request_id", requestId)
        return compactJson(node)
    }

    /**
     * Blocks until mpv exits, calling [onUpdate] with the latest position and duration (seconds)
     * and [onEvent] with every other event (`client-message` carries our script's messages).
     * Returns quietly if the IPC cannot be reached: playback simply isn't tracked then.
     */
    fun observe(
        path: String,
        process: Process,
        onEvent: (name: String, args: List<String>) -> Unit = { _, _ -> },
        onUpdate: (position: Double?, duration: Double?) -> Unit,
    ) {
        val commands = listOf(
            commandJson(listOf("observe_property", 1, "time-pos")),
            commandJson(listOf("observe_property", 2, "duration")),
        ).joinToString("\n", postfix = "\n").toByteArray()

        val connection = connectWhenReady(path, process, 20_000)
        if (connection == null) {
            Logger.info("IPC mpv indisponible, la position ne sera pas enregistrée")
            return
        }
        // Write everything before reading: a synchronous Windows pipe can't read and write at once.
        runCatching { connection.output.write(commands); connection.output.flush() }

        var position: Double? = null
        var duration: Double? = null
        try {
            connection.input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val node = runCatching { mapper.readTree(line) }.getOrNull() ?: continue
                    val event = node.path("event").asText()
                    if (event.isEmpty()) continue
                    if (event != "property-change") {
                        // A new file (next episode) starts: forget the previous one's values.
                        if (event == "start-file") {
                            position = null
                            duration = null
                        }
                        val args = node.path("args").map { it.asText() }
                        runCatching { onEvent(event, args) }.onFailure { Logger.error("IPC $event", it) }
                        continue
                    }
                    val value = node.get("data")?.takeIf { it.isNumber }?.asDouble()
                    when (node.path("name").asText()) {
                        "time-pos" -> position = value ?: position
                        "duration" -> duration = value ?: duration
                        else -> continue
                    }
                    runCatching { onUpdate(position, duration) }.onFailure { Logger.error("IPC position", it) }
                }
            }
            Logger.info("IPC mpv fermé")
        } catch (e: Exception) {
            // mpv closed the connection when quitting.
            Logger.info("IPC mpv interrompu : ${e.message}")
        } finally {
            runCatching { connection.closer.close() }
            if (!isWindows) File(path).delete()
        }
    }

    /**
     * Sends commands on a separate, short-lived connection (mpv accepts several clients),
     * waiting for each answer so they run in order. Returns false if mpv could not be reached.
     */
    fun send(path: String, vararg commands: List<Any?>): Boolean {
        val connection = connectWhenReady(path, null, 5_000) ?: return false
        return try {
            val reader = connection.input.bufferedReader(Charsets.UTF_8)
            commands.forEachIndexed { index, command ->
                val id = 1000 + index
                connection.output.write((commandJson(command, id) + "\n").toByteArray())
                connection.output.flush()
                // Other clients' events are broadcast too: skip them until our answer.
                while (true) {
                    val line = reader.readLine() ?: return false
                    val node = runCatching { mapper.readTree(line) }.getOrNull() ?: continue
                    if (node.path("request_id").asInt(-1) != id) continue
                    if (node.path("error").asText() != "success") {
                        Logger.info("mpv ${command.firstOrNull()} : ${node.path("error").asText()}")
                    }
                    break
                }
            }
            true
        } catch (t: Exception) {
            Logger.error("IPC mpv", t)
            false
        } finally {
            runCatching { connection.closer.close() }
        }
    }

    /** Sends a message to our player script (`cloudstreampc.lua`). */
    fun toScript(path: String, vararg args: String) =
        send(path, listOf("script-message-to", "cloudstreampc") + args.toList())
}
