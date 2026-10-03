package com.lagradost.cloudstream4

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Copies everything written to stdout and stderr into logs/cloudstream.log, so the installed app,
 * which has no console, still leaves a log behind. The log from the previous run is kept as
 * cloudstream.old.log.
 */
object DesktopLog {
    fun install(dir: Path) {
        try {
            dir.createDirectories()
            val file = dir.resolve("cloudstream.log")
            if (file.exists()) Files.move(file, dir.resolve("cloudstream.old.log"), REPLACE_EXISTING)

            // Buffered: the timestamps are added a byte at a time, which would otherwise mean a write to
            // disk per byte. Both streams flush at the end of every line, so the log stays current
            val log = TimestampedOutputStream(BufferedOutputStream(FileOutputStream(file.toFile())))
            // Without NiceHttp's trace for every cancelled request, see QuietPrintStream
            System.setOut(QuietPrintStream(TeeOutputStream(System.out, log)))
            System.setErr(QuietPrintStream(TeeOutputStream(System.err, log)))
        } catch (t: Throwable) {
            // Logging must never stop the app from starting
            System.err.println("Could not create log file in $dir: $t")
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            System.err.println("FATAL Uncaught exception in thread ${thread.name}")
            throwable.printStackTrace()
            previous?.uncaughtException(thread, throwable)
        }

        println(
            "INFO DesktopLog: CloudStream ${System.getProperty("jpackage.app-version") ?: "dev"} starting, " +
                    "Java ${System.getProperty("java.version")}, " +
                    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        )
    }

    private class TeeOutputStream(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            a.write(b)
            this.b.write(b)
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            a.write(bytes, off, len)
            b.write(bytes, off, len)
        }

        override fun flush() {
            a.flush()
            b.flush()
        }
    }

    /** Adds a timestamp to the start of every line. Shared by stdout and stderr, so it is synchronized */
    private class TimestampedOutputStream(private val out: OutputStream) : OutputStream() {
        private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS ")
        private var lineStart = true

        @Synchronized
        override fun write(b: Int) {
            if (lineStart) {
                out.write(LocalDateTime.now().format(format).toByteArray())
                lineStart = false
            }
            out.write(b)
            if (b == '\n'.code) lineStart = true
        }

        @Synchronized
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(bytes[i].toInt())
        }

        @Synchronized
        override fun flush() = out.flush()
    }
}
