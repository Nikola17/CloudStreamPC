package com.moncloudi.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** A track reported by mpv's `track-list` property (audio, subtitle or video). */
data class MpvTrack(
    val id: Int,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val channels: Int?,
    val external: Boolean,
    val selected: Boolean,
    val defaultTrack: Boolean,
    val forced: Boolean,
) {
    val label: String
        get() {
            val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("http") }
            val langLabel = lang?.takeIf { it.isNotBlank() && it != "und" }?.let(OnlineSubtitles::displayLanguage)
            val base = when {
                cleanTitle != null && langLabel != null && !cleanTitle.contains(langLabel, ignoreCase = true) -> "$cleanTitle ($langLabel)"
                cleanTitle != null -> cleanTitle
                langLabel != null -> langLabel
                else -> "Piste $id"
            }
            val tags = buildList {
                if (forced) add("forcé")
                if (channels != null && channels > 2) add(if (channels == 6) "5.1" else "${channels}ch")
                if (external) add("externe")
            }
            return if (tags.isEmpty()) base else "$base · ${tags.joinToString(", ")}"
        }
}

enum class VideoAspectMode(val label: String, val aspectOverride: String, val panscan: Double) {
    AUTO("Auto (original)", "-1", 0.0),
    FILL("Remplir l'écran (sans bandes)", "-1", 1.0),
    RATIO_16_9("16:9", "16:9", 0.0),
    RATIO_4_3("4:3", "4:3", 0.0),
}

/**
 * Live state and reliable IPC controls for the running mpv player (tracks, audio, subtitles,
 * delay/scale, speed, volume, aspect ratio, and uosc menu visibility).
 */
object PlayerController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentProcess: Process? = null
    @Volatile private var currentIpc: String? = null

    var tracks by mutableStateOf<List<MpvTrack>>(emptyList())
        private set
    var activeAudioId by mutableStateOf<Int?>(null)
        private set
    var activeSubId by mutableStateOf<Int?>(null)
        private set
    var subVisible by mutableStateOf(true)
        private set
    var subDelay by mutableDoubleStateOf(0.0)
        private set
    var subScale by mutableDoubleStateOf(1.0)
        private set
    var speed by mutableDoubleStateOf(1.0)
        private set
    var volume by mutableIntStateOf(100)
        private set
    var muted by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var aspectMode by mutableStateOf(VideoAspectMode.AUTO)
        private set
    var loadingOnlineSubtitles by mutableStateOf(false)
        internal set

    /** True while a uosc popup menu (Subtitles, Audio, Sources, etc.) is open inside mpv. */
    @Volatile var uoscMenuOpen: Boolean = false
        private set

    val audioTracks: List<MpvTrack> get() = tracks.filter { it.type == "audio" }
    val subtitleTracks: List<MpvTrack> get() = tracks.filter { it.type == "sub" }

    val observedProperties = listOf(
        "track-list", "aid", "sid", "sub-visibility", "sub-delay", "sub-scale",
        "speed", "volume", "mute", "pause", "video-aspect-override", "panscan",
        "user-data/uosc/menu/type",
    )

    fun attach(process: Process, ipcPath: String) {
        currentProcess = process
        currentIpc = ipcPath
        resetState()
    }

    fun detach(process: Process) {
        if (currentProcess === process) {
            currentProcess = null
            currentIpc = null
            resetState()
        }
    }

    private fun resetState() {
        uoscMenuOpen = false
        ui {
            tracks = emptyList()
            activeAudioId = null
            activeSubId = null
            subVisible = true
            subDelay = 0.0
            subScale = 1.0
            speed = 1.0
            volume = 100
            muted = false
            paused = false
            aspectMode = VideoAspectMode.AUTO
            loadingOnlineSubtitles = false
        }
    }

    /** Called by the IPC property watcher whenever one of [observedProperties] changes in mpv. */
    fun onProperty(process: Process, name: String, value: JsonNode) {
        if (process !== currentProcess) return
        when (name) {
            "user-data/uosc/menu/type" -> {
                uoscMenuOpen = !value.isNull && value.asText("").isNotBlank()
            }
            "track-list" -> {
                val parsed = parseTracks(value)
                ui { tracks = parsed }
            }
            "aid" -> ui { activeAudioId = value.takeIf { it.isInt || it.isLong }?.asInt() }
            "sid" -> ui { activeSubId = value.takeIf { it.isInt || it.isLong }?.asInt() }
            "sub-visibility" -> ui { subVisible = value.asBoolean(true) }
            "sub-delay" -> ui { subDelay = (value.asDouble(0.0) * 100.0).roundToInt() / 100.0 }
            "sub-scale" -> ui { subScale = (value.asDouble(1.0) * 100.0).roundToInt() / 100.0 }
            "speed" -> ui { speed = (value.asDouble(1.0) * 100.0).roundToInt() / 100.0 }
            "volume" -> ui { volume = value.asDouble(100.0).roundToInt().coerceIn(0, 150) }
            "mute" -> ui { muted = value.asBoolean(false) }
            "pause" -> ui { paused = value.asBoolean(false) }
            "panscan", "video-aspect-override" -> {
                val ps = if (name == "panscan") value.asDouble(0.0) else null
                val ar = if (name == "video-aspect-override") value.asText("-1") else null
                ui {
                    aspectMode = when {
                        ps != null && ps > 0.5 -> VideoAspectMode.FILL
                        ar == "16:9" || ar == "1.777778" -> VideoAspectMode.RATIO_16_9
                        ar == "4:3" || ar == "1.333333" -> VideoAspectMode.RATIO_4_3
                        ps != null && ps <= 0.5 && aspectMode == VideoAspectMode.FILL -> VideoAspectMode.AUTO
                        ar == "-1" && aspectMode != VideoAspectMode.FILL -> VideoAspectMode.AUTO
                        else -> aspectMode
                    }
                }
            }
        }
    }

    internal fun parseTracks(node: JsonNode): List<MpvTrack> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { item ->
            val id = item.get("id")?.asInt() ?: return@mapNotNull null
            val type = item.get("type")?.asText() ?: return@mapNotNull null
            MpvTrack(
                id = id,
                type = type,
                title = item.get("title")?.takeIf { !it.isNull }?.asText(),
                lang = item.get("lang")?.takeIf { !it.isNull }?.asText(),
                codec = item.get("codec")?.takeIf { !it.isNull }?.asText(),
                channels = item.get("audio-channels")?.takeIf { it.isInt }?.asInt(),
                external = item.path("external").asBoolean(false),
                selected = item.path("selected").asBoolean(false),
                defaultTrack = item.path("default").asBoolean(false),
                forced = item.path("forced").asBoolean(false),
            )
        }
    }

    // ----- Reliable IPC actions -----

    private fun ipc(): String? = currentIpc ?: PlayerSurface.ipcPath ?: PlaybackManager.currentIpc

    fun selectAudio(id: Int) {
        val path = ipc() ?: return
        val label = audioTracks.firstOrNull { it.id == id }?.label ?: "Piste $id"
        ui { activeAudioId = id }
        scope.launch {
            MpvIpc.send(path, listOf("set_property", "aid", id))
            MpvIpc.toScript(path, "status", "Audio : $label")
        }
    }

    fun selectSubtitle(id: Int?) {
        val path = ipc() ?: return
        val label = if (id == null) "Désactivés" else subtitleTracks.firstOrNull { it.id == id }?.label ?: "Piste $id"
        ui {
            activeSubId = id
            if (id != null) subVisible = true
        }
        scope.launch {
            if (id == null) {
                MpvIpc.send(path, listOf("set_property", "sid", "no"))
            } else {
                MpvIpc.send(
                    path,
                    listOf("set_property", "sid", id),
                    listOf("set_property", "sub-visibility", true),
                )
            }
            MpvIpc.toScript(path, "status", "Sous-titres : $label")
        }
    }

    fun changeSubDelay(seconds: Double) {
        val path = ipc() ?: return
        val rounded = (seconds * 100.0).roundToInt() / 100.0
        ui { subDelay = rounded }
        scope.launch {
            MpvIpc.send(path, listOf("set_property", "sub-delay", rounded))
            MpvIpc.toScript(path, "status", "Décalage sous-titres : %+.2f s".format(rounded))
        }
    }

    fun adjustSubDelay(delta: Double) = changeSubDelay(subDelay + delta)

    fun changeSubScale(scale: Double) {
        val path = ipc() ?: return
        val clamped = ((scale.coerceIn(0.5, 2.0)) * 100.0).roundToInt() / 100.0
        ui { subScale = clamped }
        scope.launch {
            MpvIpc.send(path, listOf("set_property", "sub-scale", clamped))
            MpvIpc.toScript(path, "status", "Taille sous-titres : ${(clamped * 100).roundToInt()} %")
        }
    }

    fun adjustSubScale(delta: Double) = changeSubScale(subScale + delta)

    fun addLocalSubtitle(file: File) {
        val path = ipc() ?: return
        if (!file.isFile) return
        scope.launch {
            MpvIpc.send(
                path,
                listOf("sub-add", file.absolutePath, "select", file.nameWithoutExtension),
                listOf("set_property", "sub-visibility", true),
            )
            MpvIpc.toScript(path, "status", "Sous-titres chargés : ${file.name}")
        }
    }

    fun changeSpeed(value: Double) {
        val path = ipc() ?: return
        val clamped = value.coerceIn(0.25, 3.0)
        ui { speed = clamped }
        scope.launch {
            MpvIpc.send(path, listOf("set_property", "speed", clamped))
            MpvIpc.toScript(path, "status", "Vitesse : ${clamped}x".replace(".0x", "x"))
        }
    }

    fun setAspect(mode: VideoAspectMode) {
        val path = ipc() ?: return
        ui { aspectMode = mode }
        scope.launch {
            MpvIpc.send(
                path,
                listOf("set_property", "video-aspect-override", mode.aspectOverride),
                listOf("set_property", "panscan", mode.panscan),
            )
            MpvIpc.toScript(path, "status", "Format : ${mode.label}")
        }
    }

    fun changeVolume(vol: Int) {
        val path = ipc() ?: return
        val clamped = vol.coerceIn(0, 150)
        ui { volume = clamped; if (clamped > 0) muted = false }
        scope.launch {
            MpvIpc.send(path, listOf("set_property", "volume", clamped), listOf("set_property", "mute", false))
        }
    }

    fun toggleMute() {
        val path = ipc() ?: return
        val next = !muted
        ui { muted = next }
        scope.launch { MpvIpc.send(path, listOf("set_property", "mute", next)) }
    }

    fun togglePause() {
        val path = ipc() ?: return
        scope.launch { MpvIpc.send(path, listOf("cycle", "pause")) }
    }

    private fun ui(block: () -> Unit) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) block() else javax.swing.SwingUtilities.invokeLater(block)
    }
}
