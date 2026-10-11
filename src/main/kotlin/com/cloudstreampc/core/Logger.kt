package com.cloudstreampc.core

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Logger {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    val file: File = File(AppDirs.logs, "cloudstreampc.log")

    /** Mirrors stdout/stderr (including the CloudStream library's logs) into a log file. */
    fun install() {
        if (file.exists() && file.length() > 5_000_000) file.delete()
        val fileOut = PrintStream(FileOutputStream(file, true), true, Charsets.UTF_8)
        System.setOut(TeeStream(System.out, fileOut))
        System.setErr(TeeStream(System.err, fileOut))
        info("=== CloudStreamPC démarré ${LocalDateTime.now()} ===")
    }

    fun info(message: String) = println("[${time.format(LocalDateTime.now())}] $message")

    fun error(message: String, t: Throwable? = null) {
        System.err.println("[${time.format(LocalDateTime.now())}] ERREUR $message")
        t?.printStackTrace()
    }

    private class TeeStream(private val a: PrintStream, private val b: PrintStream) :
        PrintStream(a, true, Charsets.UTF_8) {
        override fun write(buf: ByteArray, off: Int, len: Int) {
            a.write(buf, off, len)
            b.write(buf, off, len)
        }

        override fun write(b0: Int) {
            a.write(b0)
            b.write(b0)
        }

        override fun flush() {
            a.flush()
            b.flush()
        }
    }
}
