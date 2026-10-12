package com.cloudstreampc.core

import com.lagradost.cloudstream3.app
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object Updates {
    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val version: String,
        val url: String,
        val notes: String?,
        /** Installer for this computer's system, when the release has one. */
        val installer: Asset? = null,
        val checksums: Asset? = null,
    )

    internal var api = "https://api.github.com/repos/Nikola17/CloudStreamPC/releases/latest"

    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = "win" in os
    private val isMac = "mac" in os
    private val arch = System.getProperty("os.arch").lowercase()

    /** Latest CloudStreamPC release on GitHub, if newer than [current]. */
    suspend fun check(current: String): Release? {
        val node = mapper.readTree(app.get(api, cacheTime = 0).text)
        val tag = node.get("tag_name")?.asText()?.removePrefix("v") ?: return null
        if (!isNewer(tag, current)) return null
        val assets = node.path("assets").map { Asset(it.path("name").asText(), it.path("browser_download_url").asText(), it.path("size").asLong()) }
        return Release(
            tag,
            node.get("html_url")?.asText().orEmpty(),
            node.get("body")?.asText(),
            installerNames(tag).firstNotNullOfOrNull { name -> assets.firstOrNull { it.name == name } },
            assets.firstOrNull { it.name == "SHA256SUMS.txt" },
        )
    }

    /** Release files that install this version here, best first (names given by the build, see build.yml). */
    internal fun installerNames(version: String): List<String> = when {
        isWindows -> listOf("CloudStreamPC-$version-setup.exe")
        isMac -> listOf(if ("aarch64" in arch || "arm64" in arch) "CloudStreamPC-$version-macos-arm64.dmg" else "CloudStreamPC-$version-macos-x64.dmg")
        File("/usr/bin/dpkg").exists() -> listOf("CloudStreamPC-$version-amd64.deb")
        File("/usr/bin/rpm").exists() -> listOf("CloudStreamPC-$version-x86_64.rpm")
        else -> emptyList()
    }

    /**
     * Downloads the installer and checks it against the release's SHA256SUMS.txt: a file that does
     * not match is deleted and never run.
     */
    fun download(release: Release, onProgress: (Float) -> Unit = {}): File {
        val installer = release.installer ?: error("Pas d'installateur pour ce système")
        val sums = release.checksums ?: error("Empreintes SHA-256 absentes de la version")
        val expected = fetch(sums.url).body.string().lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == installer.name }?.get(0)?.lowercase()
            ?: error("Empreinte de ${installer.name} introuvable")
        val dir = File(AppDirs.cache, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, installer.name)
        val digest = MessageDigest.getInstance("SHA-256")
        fetch(installer.url).use { response ->
            val total = response.body.contentLength().takeIf { it > 0 } ?: installer.size
            response.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            error("Fichier téléchargé corrompu (empreinte SHA-256 différente)")
        }
        return file
    }

    private fun fetch(url: String) = app.baseClient.newCall(Request.Builder().url(url).header("User-Agent", "CloudStreamPC").build())
        .execute().also { if (!it.isSuccessful) { it.close(); error("HTTP ${it.code}") } }

    /** Opens the installer (Windows setup, .dmg, or the system's package installer); the app then quits. */
    fun launch(installer: File) {
        val command = when {
            // Inno Setup: progress window only, closes the app, installs, then starts the new version.
            isWindows -> listOf(installer.path, "/SILENT", "/SP-", "/NOCANCEL", "/CLOSEAPPLICATIONS", "/LANG=" + if (com.cloudstreampc.ui.I18n.language == "fr") "fr" else "en")
            isMac -> listOf("open", installer.path)
            else -> listOf("xdg-open", installer.path)
        }
        ProcessBuilder(command).start()
        Logger.info("Installateur lancé : ${installer.name}")
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
