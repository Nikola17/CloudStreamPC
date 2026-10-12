package com.cloudstreampc.providers

import com.cloudstreampc.core.AppDirs
import com.cloudstreampc.core.Logger
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** A TV programme; times are epoch milliseconds. */
data class Programme(val start: Long, val stop: Long, val title: String, val description: String? = null) {
    fun isOn(now: Long) = now in start until stop
    fun progress(now: Long) = ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** Reads XMLTV guides (plain or gzipped), keeping only a time window to save memory. */
object XmlTv {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss[ Z]")

    fun parseTime(value: String): Long? = runCatching {
        val text = value.trim()
        if (text.length > 14) {
            OffsetDateTime.parse(text, timeFormat).toInstant().toEpochMilli()
        } else {
            java.time.LocalDateTime.parse(text.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    }.getOrNull()

    /**
     * @param channels channel ids to keep (lower case), or null for every channel
     * @return programmes by lower-case channel id, sorted by start time
     */
    fun parse(input: InputStream, from: Long, to: Long, channels: Set<String>?): Map<String, List<Programme>> {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered

        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        val reader = factory.createXMLStreamReader(stream, "UTF-8")
        val result = HashMap<String, MutableList<Programme>>()
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var description: String? = null
        var field: String? = null
        val text = StringBuilder()
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "programme" -> {
                            val id = reader.getAttributeValue(null, "channel")?.lowercase()
                            val begin = reader.getAttributeValue(null, "start")?.let(::parseTime)
                            val end = reader.getAttributeValue(null, "stop")?.let(::parseTime)
                            val wanted = id != null && begin != null && end != null && end > from && begin < to &&
                                (channels == null || id in channels)
                            channel = if (wanted) id else null
                            start = begin ?: 0
                            stop = end ?: 0
                            title = null
                            description = null
                        }
                        "title", "desc" -> if (channel != null) {
                            field = reader.localName
                            text.setLength(0)
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (field != null) text.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "title" -> if (field == "title") {
                            if (title == null) title = text.toString().trim()
                            field = null
                        }
                        "desc" -> if (field == "desc") {
                            if (description == null) description = text.toString().trim()
                            field = null
                        }
                        "programme" -> {
                            val id = channel
                            val name = title
                            if (id != null && !name.isNullOrBlank()) {
                                result.getOrPut(id) { mutableListOf() } += Programme(start, stop, name, description?.ifBlank { null })
                            }
                            channel = null
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }
        return result.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
    }
}

/** The guide of one IPTV account, cached on disk and refreshed every 12 hours. */
class EpgStore(private val key: String) {
    private val mutex = Mutex()
    private val file get() = File(AppDirs.cache, "epg-$key.xml")

    @Volatile
    var programmes: Map<String, List<Programme>> = emptyMap()
        private set
    @Volatile
    private var loadedAt = 0L

    val isLoaded get() = loadedAt > 0

    /** Downloads (or reads from the cache) the guide for [channels] (lower-case XMLTV ids). */
    suspend fun ensure(url: String?, channels: Set<String>): Map<String, List<Programme>> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (url == null || channels.isEmpty()) return programmes
        if (now - loadedAt < 30 * 60_000L && programmes.isNotEmpty()) return programmes
        withContext(Dispatchers.IO) {
            try {
                val fresh = file.isFile && now - file.lastModified() < 12 * 3600_000L && file.length() > 0
                if (!fresh) {
                    val tmp = File(file.path + ".part")
                    app.get(url, timeout = 300).body.byteStream().use { input ->
                        tmp.outputStream().use { input.copyTo(it, 256 * 1024) }
                    }
                    if (tmp.length() > 0) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                }
                if (file.isFile) {
                    programmes = file.inputStream().use { XmlTv.parse(it, now - 25 * 3600_000L, now + 26 * 3600_000L, channels) }
                    loadedAt = now
                    Logger.info("Guide TV : ${programmes.size} chaînes, ${programmes.values.sumOf { it.size }} programmes")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.error("Guide TV ($url)", t)
            }
        }
        programmes
    }

    fun current(channel: String?, now: Long = System.currentTimeMillis()): Programme? =
        channel?.let { programmes[it.lowercase()] }?.firstOrNull { it.isOn(now) }

    fun next(channel: String?, now: Long = System.currentTimeMillis()): Programme? =
        channel?.let { programmes[it.lowercase()] }?.firstOrNull { it.start >= now }
}
