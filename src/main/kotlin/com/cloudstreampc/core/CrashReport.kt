package com.cloudstreampc.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Crash and problem reports: what happened, the system, and the end of the log, with passwords,
 * tokens and keys removed so the report can be posted on GitHub as it is.
 */
object CrashReport {
    internal var issuesUrl = "https://github.com/Nikola17/CloudStreamPC/issues/new"
    private const val MAX_URL = 7_500

    fun build(version: String, error: Throwable?, logLines: Int = 120): String {
        val log = runCatching { Logger.file.readLines().takeLast(logLines).joinToString("\n") }.getOrDefault("")
        val stack = error?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }
        val text = buildString {
            appendLine("CloudStreamPC $version")
            appendLine("Système : ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("Java : ${System.getProperty("java.version")}")
            appendLine("Date : ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            if (stack != null) {
                appendLine()
                appendLine("Erreur :")
                appendLine(stack.trim())
            }
            if (log.isNotBlank()) {
                appendLine()
                appendLine("Fin du journal :")
                appendLine(log)
            }
        }
        return redact(text, secrets())
    }

    /** Values that must never leave the computer. */
    private fun secrets(): List<String> = runCatching {
        val s = Store.settings
        s.iptvAccounts.flatMap { listOf(it.username, it.password) } +
            s.trackers.values.flatMap { listOfNotNull(it.clientSecret, it.accessToken, it.refreshToken) } +
            listOf(s.tmdbApiKey) + s.profiles.mapNotNull { it.pin }
    }.getOrDefault(emptyList())

    internal fun redact(text: String, secrets: List<String>): String {
        var out = text
        secrets.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { out = out.replace(it, "***") }
        // Credentials in URLs: ?password=…, api_key=…, user:pass@host, Xtream /live/user/pass/ paths.
        out = Regex("""(?i)\b(username|password|pass|token|access_token|refresh_token|api_key|apikey|client_secret|code)=([^&\s"']+)""")
            .replace(out) { "${it.groupValues[1]}=***" }
        out = Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@""").replace(out) { "${it.groupValues[1]}***:***@" }
        out = Regex("""(?i)/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""").replace(out) { "/${it.groupValues[1]}/***/***/" }
        out = Regex("""(?i)(authorization:\s*(?:(?:bearer|basic)\s+)?|bearer\s+)\S+""").replace(out) { "${it.groupValues[1]}***" }
        return out
    }

    fun save(report: String): File =
        File(AppDirs.logs, "crash-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt")
            .apply { writeText(report) }

    /** GitHub "new issue" page filled with the report, shortened to fit in a link (oldest log lines go first). */
    fun issueUrl(title: String, report: String): String {
        fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        val start = "$issuesUrl?title=${encode(title)}&body="
        fun url(text: String) = start + encode("```\n$text\n```")
        var lines = report.lines()
        val logStart = lines.indexOf("Fin du journal :") + 1
        while (url(lines.joinToString("\n")).length > MAX_URL && logStart in 1 until lines.size) {
            lines = lines.take(logStart) + lines.drop(logStart + 1)
        }
        var text = lines.joinToString("\n")
        while (url(text).length > MAX_URL) text = text.dropLast(200)
        return url(text)
    }

    /**
     * Crashes of Java itself (the app closed without a word): the JVM leaves an `hs_err_pid*.log` next to
     * the app (see `-XX:ErrorFile` in build.gradle.kts) or in the temporary folder. At the next start its
     * summary goes into the log (so into "Signaler un problème") and the file into the logs folder.
     */
    fun collectJvmCrashes() {
        runCatching {
            val places = listOfNotNull(
                System.getProperty("jpackage.app-path")?.let { File(it).parentFile },
                File(System.getProperty("user.dir")),
                File(System.getProperty("java.io.tmpdir")),
            ).distinct()
            val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
            places.flatMap { dir -> dir.listFiles { f -> f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }.orEmpty().toList() }
                .filter { it.lastModified() > weekAgo }
                .forEach { file ->
                    val summary = file.readLines().filter { it.isNotBlank() }.take(30).joinToString("\n")
                    Logger.error("Plantage de Java lors d'une session précédente (${file.name}) :\n$summary", null)
                    val kept = File(AppDirs.logs, "crash-java-${file.name.removePrefix("hs_err_")}")
                    if (!runCatching { file.renameTo(kept) }.getOrDefault(false)) {
                        runCatching { file.copyTo(kept, overwrite = true); file.delete() }
                    }
                }
        }
    }

    /** Errors no code catches: logged, and the app keeps going when it can. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Logger.error("Erreur non gérée (${thread.name})", error)
            if (previous != null && thread.name.startsWith("AWT-EventQueue")) previous.uncaughtException(thread, error)
        }
    }
}
