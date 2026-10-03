package com.lagradost.cloudstream4

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuietPrintStreamTest {
    @Test
    fun cancelledRequestsLeaveNothingAndEverythingElsePasses() {
        val bytes = ByteArrayOutputStream()
        val out = QuietPrintStream(bytes)
        out.println("INFO before")
        // What NiceHttp prints for a request an extension cancelled
        out.println("Exception in NiceHttp: java.io.IOException Canceled")
        IOException("Canceled").apply { addSuppressed(SocketTimeoutException("Connect timed out")) }.printStackTrace(out)
        // A real error is kept, with its trace
        IOException("Connection reset").printStackTrace(out)
        out.println("INFO after")
        out.flush()

        val lines = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals("INFO before", lines.first())
        assertEquals("java.io.IOException: Connection reset", lines[1])
        assertTrue(lines[2].startsWith("\tat "))
        assertEquals("INFO after", lines.last())
        assertTrue(lines.none { "Canceled" in it || "Connect timed out" in it }, lines.joinToString("\n"))
    }
}
