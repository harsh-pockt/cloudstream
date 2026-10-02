package android.text

class TextUtils private constructor() {
    companion object {
        @JvmStatic fun isEmpty(str: CharSequence?): Boolean = str.isNullOrEmpty()
        @JvmStatic fun isDigitsOnly(str: CharSequence?): Boolean = str!!.all { it.isDigit() }
        @JvmStatic fun equals(a: CharSequence?, b: CharSequence?): Boolean = a?.toString() == b?.toString()
        @JvmStatic fun join(delimiter: CharSequence?, tokens: Array<Any?>?): String = tokens!!.joinToString(delimiter.toString())
        @JvmStatic fun join(delimiter: CharSequence?, tokens: Iterable<*>?): String = tokens!!.joinToString(delimiter.toString())
        @JvmStatic fun split(text: String?, expression: String?): Array<String> =
            if (text.isNullOrEmpty()) emptyArray() else text.split(Regex(expression!!)).toTypedArray()
        @JvmStatic fun getTrimmedLength(s: CharSequence?): Int = s!!.trim().length

        @JvmStatic
        fun htmlEncode(s: String?): String = buildString {
            for (c in s!!) when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '\'' -> append("&#39;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}
