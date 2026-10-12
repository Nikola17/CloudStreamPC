package com.cloudstreampc.player

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.core.compactJson
import com.cloudstreampc.core.mapper
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.Executors

/**
 * "Regarde MobLand · S1·E3" on the user's Discord profile, through the local Discord IPC
 * (named pipe on Windows, unix socket elsewhere). Needs the id of a Discord application
 * the user created (Discord shows its name above the activity).
 */
object DiscordPresence {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    /** One thread: the Windows pipe is synchronous, writes and reads must not overlap. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "discord-presence").apply { isDaemon = true } }
    private var connection: Triple<InputStream, OutputStream, AutoCloseable>? = null
    private var connectedAppId: String? = null

    private fun open(): Triple<InputStream, OutputStream, AutoCloseable>? {
        for (i in 0..9) {
            try {
                if (isWindows) {
                    val pipe = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
                    return Triple(Channels.newInputStream(pipe.channel), Channels.newOutputStream(pipe.channel), pipe)
                }
                val dirs = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), "/tmp")
                for (dir in dirs) {
                    val socket = File(dir, "discord-ipc-$i").takeIf { it.exists() } ?: continue
                    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                    channel.connect(UnixDomainSocketAddress.of(socket.path))
                    return Triple(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel)
                }
            } catch (_: Exception) {
                // Next pipe number.
            }
        }
        return null
    }

    internal fun frame(opcode: Int, payload: Any): ByteArray {
        val json = compactJson(payload).toByteArray()
        return ByteBuffer.allocate(8 + json.size).order(ByteOrder.LITTLE_ENDIAN).putInt(opcode).putInt(json.size).put(json).array()
    }

    private fun read(input: InputStream): Pair<Int, String> {
        val data = DataInputStream(input)
        val header = ByteArray(8).also { data.readFully(it) }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = buffer.int
        val payload = ByteArray(buffer.int).also { data.readFully(it) }
        return opcode to String(payload)
    }

    private fun send(opcode: Int, payload: Any): String? {
        val (input, output) = connection ?: return null
        output.write(frame(opcode, payload))
        output.flush()
        return read(input).second
    }

    private fun disconnect() {
        runCatching { connection?.third?.close() }
        connection = null
        connectedAppId = null
    }

    private fun connected(appId: String): Boolean {
        if (connection != null && connectedAppId == appId) return true
        disconnect()
        connection = open() ?: return false
        return try {
            val ready = send(0, mapOf("v" to 1, "client_id" to appId))
            if (ready == null || mapper.readTree(ready).path("evt").asText() != "READY") {
                Logger.info("Discord a refusé l'identifiant d'application")
                disconnect()
                false
            } else {
                connectedAppId = appId
                true
            }
        } catch (t: Exception) {
            disconnect()
            false
        }
    }

    private fun setActivity(activity: Map<String, Any?>?) {
        val settings = Store.settings
        val appId = settings.discordAppId.trim()
        if (!settings.discordPresence || appId.isEmpty()) {
            if (connection != null) disconnect()
            return
        }
        worker.execute {
            try {
                if (!connected(appId)) return@execute
                val args = mapOf("pid" to ProcessHandle.current().pid(), "activity" to activity)
                send(1, mapOf("cmd" to "SET_ACTIVITY", "args" to args, "nonce" to UUID.randomUUID().toString()))
            } catch (t: Exception) {
                // Discord was closed: reconnect next time.
                disconnect()
            }
        }
    }

    /** Shows what is playing; [startedAt] lets Discord count the elapsed time. */
    fun watching(request: PlayRequest, startedAt: Long = System.currentTimeMillis()) {
        val playable = request.playable
        setActivity(
            mapOf(
                "type" to 3, // "Watching"
                "details" to playable.title.take(128),
                "state" to (playable.label?.substringBefore("  ") ?: if (request.isLive) "En direct" else "Film").take(128),
                "timestamps" to mapOf("start" to startedAt / 1000),
                "assets" to buildMap {
                    request.response.posterUrl?.takeIf { it.startsWith("https://") }?.let { put("large_image", it) }
                    put("large_text", playable.title.take(128))
                },
            ),
        )
    }

    fun clear() = setActivity(null)
}
