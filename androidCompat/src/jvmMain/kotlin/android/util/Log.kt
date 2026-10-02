package android.util

import java.io.PrintWriter
import java.io.StringWriter

/** Android's Log, printed in the app's "LEVEL tag: message" format */
class Log private constructor() {
    companion object {
        const val VERBOSE = 2
        const val DEBUG = 3
        const val INFO = 4
        const val WARN = 5
        const val ERROR = 6
        const val ASSERT = 7

        private fun write(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
            val line = "$level $tag: $msg" + (tr?.let { "\n" + getStackTraceString(it) } ?: "")
            kotlin.io.println(line)
            return line.length
        }

        @JvmStatic fun v(tag: String?, msg: String?): Int = write("VERBOSE", tag, msg)
        @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = write("VERBOSE", tag, msg, tr)
        @JvmStatic fun d(tag: String?, msg: String?): Int = write("DEBUG", tag, msg)
        @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = write("DEBUG", tag, msg, tr)
        @JvmStatic fun i(tag: String?, msg: String?): Int = write("INFO", tag, msg)
        @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = write("INFO", tag, msg, tr)
        @JvmStatic fun w(tag: String?, msg: String?): Int = write("WARNING", tag, msg)
        @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write("WARNING", tag, msg, tr)
        @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write("WARNING", tag, "", tr)
        @JvmStatic fun e(tag: String?, msg: String?): Int = write("ERROR", tag, msg)
        @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write("ERROR", tag, msg, tr)
        @JvmStatic fun wtf(tag: String?, msg: String?): Int = write("ERROR", tag, msg)
        @JvmStatic fun wtf(tag: String?, tr: Throwable?): Int = write("ERROR", tag, "", tr)
        @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = write("ERROR", tag, msg, tr)
        @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int = write("LOG$priority", tag, msg)
        @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = level >= INFO

        @JvmStatic
        fun getStackTraceString(tr: Throwable?): String {
            if (tr == null) return ""
            val out = StringWriter()
            tr.printStackTrace(PrintWriter(out))
            return out.toString()
        }
    }
}
