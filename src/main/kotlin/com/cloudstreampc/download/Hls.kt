package com.cloudstreampc.download

import java.net.URI

/** Minimal HLS playlist parsing for downloads (master playlists, segments, AES-128 keys, init maps). */
object Hls {
    data class Variant(val url: String, val bandwidth: Long, val height: Int?)
    data class Key(val method: String, val uri: String?, val iv: ByteArray?)
    data class Segment(val url: String, val key: Key?, val sequence: Long)
    data class Media(val segments: List<Segment>, val initUrl: String?)

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    private fun resolve(base: String, ref: String): String = URI(base).resolve(ref.trim()).toString()

    private fun attributes(line: String): Map<String, String> =
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(line.substringAfter(":"))
            .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun variants(text: String, baseUrl: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val attrs = attributes(line)
            Variant(
                resolve(baseUrl, uri),
                attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
                attrs["RESOLUTION"]?.substringAfter("x")?.toIntOrNull(),
            )
        }
    }

    fun media(text: String, baseUrl: String): Media {
        var key: Key? = null
        var sequence = 0L
        var initUrl: String? = null
        val segments = mutableListOf<Segment>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> sequence = line.substringAfter(":").trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY") -> {
                    val attrs = attributes(line)
                    val method = attrs["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else Key(
                        method,
                        attrs["URI"]?.let { resolve(baseUrl, it) },
                        attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let(::hexToBytes),
                    )
                }
                line.startsWith("#EXT-X-MAP") -> initUrl = attributes(line)["URI"]?.let { resolve(baseUrl, it) }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> segments += Segment(resolve(baseUrl, line), key, sequence++)
            }
        }
        return Media(segments, initUrl)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.padStart(32, '0')
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** AES-128 IV: explicit, or the segment sequence number as a 16-byte big-endian value. */
    fun ivFor(segment: Segment): ByteArray = segment.key?.iv ?: ByteArray(16).also { iv ->
        var value = segment.sequence
        for (i in 15 downTo 8) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
    }
}
