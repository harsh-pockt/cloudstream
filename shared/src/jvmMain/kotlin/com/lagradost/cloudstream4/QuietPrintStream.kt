package com.lagradost.cloudstream4

import java.io.OutputStream
import java.io.PrintStream

/**
 * A console stream that leaves out NiceHttp's noise about cancelled requests. When an extension stops
 * its other requests, after one failed or once it has what it needs, NiceHttp prints a line and a
 * whole stack trace for each: "Exception in NiceHttp: java.io.IOException Canceled", then
 * "java.io.IOException: Canceled" and its frames. Android sends that to its system log, where nobody
 * sees it; here it filled the app's log with dozens of traces at once. Every other line passes.
 *
 * A stack trace is printed under the stream's lock, so its lines come one after another.
 */
class QuietPrintStream(out: OutputStream) : PrintStream(out, true, Charsets.UTF_8) {
    /** Inside a dropped trace: its frames and causes go too */
    private var dropping = false

    @Synchronized
    override fun println(x: Any?) = filter(x.toString()) { super.println(x) }

    @Synchronized
    override fun println(x: String?) = filter(x.toString()) { super.println(x) }

    private inline fun filter(line: String, print: () -> Unit) {
        when {
            line == CANCELED_LINE -> return
            line == CANCELED_TRACE -> dropping = true
            // Frames, "... 12 more", suppressed and causes are indented or start with "Caused by"
            dropping && (line.startsWith("\t") || line.startsWith("Caused by: ")) -> {}
            else -> {
                dropping = false
                print()
            }
        }
    }

    companion object {
        private const val CANCELED_LINE = "Exception in NiceHttp: java.io.IOException Canceled"
        private const val CANCELED_TRACE = "java.io.IOException: Canceled"
    }
}
