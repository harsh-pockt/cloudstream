package android.util

/**
 * Android's Base64, which differs from java.util.Base64 in ways extensions depend on: DEFAULT
 * encoding wraps lines at 76 characters and ends with a newline, and decoding skips characters
 * outside the alphabet (such as whitespace) and accepts missing padding.
 */
class Base64 private constructor() {
    companion object {
        const val DEFAULT = 0
        const val NO_PADDING = 1
        const val NO_WRAP = 2
        const val CRLF = 4
        const val URL_SAFE = 8
        const val NO_CLOSE = 16

        private const val LINE_LENGTH = 76

        @JvmStatic
        fun decode(str: String?, flags: Int): ByteArray = decode(str!!.toByteArray(Charsets.US_ASCII), flags)

        @JvmStatic
        fun decode(input: ByteArray?, flags: Int): ByteArray = decode(input, 0, input!!.size, flags)

        @JvmStatic
        fun decode(input: ByteArray?, offset: Int, len: Int, flags: Int): ByteArray {
            val urlSafe = flags and URL_SAFE != 0
            val data = StringBuilder(len)
            var padding = false
            for (i in offset until offset + len) {
                val c = (input!![i].toInt() and 0xff).toChar()
                when {
                    c == '=' -> padding = true
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                        (urlSafe && (c == '-' || c == '_')) || (!urlSafe && (c == '+' || c == '/')) -> {
                        if (padding) throw IllegalArgumentException("bad base-64")
                        data.append(if (c == '-') '+' else if (c == '_') '/' else c)
                    }
                    // Android skips every other character
                }
            }
            if (data.length % 4 == 1) throw IllegalArgumentException("bad base-64")
            while (data.length % 4 != 0) data.append('=')
            return java.util.Base64.getDecoder().decode(data.toString())
        }

        @JvmStatic
        fun encodeToString(input: ByteArray?, flags: Int): String = String(encode(input, flags), Charsets.US_ASCII)

        @JvmStatic
        fun encodeToString(input: ByteArray?, offset: Int, len: Int, flags: Int): String =
            String(encode(input, offset, len, flags), Charsets.US_ASCII)

        @JvmStatic
        fun encode(input: ByteArray?, flags: Int): ByteArray = encode(input, 0, input!!.size, flags)

        @JvmStatic
        fun encode(input: ByteArray?, offset: Int, len: Int, flags: Int): ByteArray {
            var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
            if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
            val text = encoder.encodeToString(input!!.copyOfRange(offset, offset + len))
            if (flags and NO_WRAP != 0) return text.toByteArray(Charsets.US_ASCII)
            val newline = if (flags and CRLF != 0) "\r\n" else "\n"
            val wrapped = StringBuilder()
            for (start in text.indices step LINE_LENGTH) {
                wrapped.append(text, start, minOf(text.length, start + LINE_LENGTH)).append(newline)
            }
            return wrapped.toString().toByteArray(Charsets.US_ASCII)
        }
    }
}
