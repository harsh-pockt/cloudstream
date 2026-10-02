package android.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Android's Uri. Like Android, parsing never fails: the string is split with the RFC 3986 regex,
 * and getters decode on the way out.
 */
open class Uri private constructor(private val uriString: String) : Comparable<Uri> {
    private val parts by lazy { URI_REGEX.matchEntire(uriString) }
    private fun part(group: Int): String? = parts?.groups?.get(group)?.value

    open fun getScheme(): String? = part(2)
    open fun getEncodedAuthority(): String? = part(4)
    open fun getAuthority(): String? = getEncodedAuthority()?.let { decode(it) }
    open fun getEncodedPath(): String? = part(5)
    open fun getPath(): String? = getEncodedPath()?.let { decode(it) }
    open fun getEncodedQuery(): String? = part(7)
    open fun getQuery(): String? = getEncodedQuery()?.let { decode(it) }
    open fun getEncodedFragment(): String? = part(9)
    open fun getFragment(): String? = getEncodedFragment()?.let { decode(it) }
    open fun getSchemeSpecificPart(): String? = uriString.substringAfter(':', uriString).substringBefore('#')
    open fun isAbsolute(): Boolean = getScheme() != null
    open fun isRelative(): Boolean = !isAbsolute()
    open fun isHierarchical(): Boolean = !isAbsolute() || getSchemeSpecificPart()?.startsWith("/") == true

    open fun getUserInfo(): String? = getEncodedAuthority()?.takeIf { '@' in it }?.substringBefore('@')?.let { decode(it) }

    open fun getHost(): String? {
        val hostPort = getEncodedAuthority()?.substringAfterLast('@') ?: return null
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return decode(host)?.ifEmpty { null }
    }

    open fun getPort(): Int {
        val hostPort = getEncodedAuthority()?.substringAfterLast('@') ?: return -1
        return hostPort.substringAfter(']', hostPort).substringAfter(':', "").toIntOrNull() ?: -1
    }

    open fun getPathSegments(): List<String> =
        getEncodedPath()?.split('/')?.filter { it.isNotEmpty() }?.map { decode(it)!! }.orEmpty()

    open fun getLastPathSegment(): String? = getPathSegments().lastOrNull()

    private fun queryPairs(): List<Pair<String, String>> = getEncodedQuery()?.split('&')?.filter { it.isNotEmpty() }?.map {
        percentDecode(it.substringBefore('='), plusAsSpace = true) to percentDecode(it.substringAfter('=', ""), plusAsSpace = true)
    }.orEmpty()

    /** Like Android, "+" in the query reads as a space */
    open fun getQueryParameter(key: String?): String? = queryPairs().firstOrNull { it.first == key }?.second
    open fun getQueryParameters(key: String?): List<String> = queryPairs().filter { it.first == key }.map { it.second }
    open fun getQueryParameterNames(): Set<String> = queryPairs().mapTo(LinkedHashSet()) { it.first }
    open fun getBooleanQueryParameter(key: String?, defaultValue: Boolean): Boolean =
        getQueryParameter(key)?.let { it != "false" && it != "0" } ?: defaultValue

    open fun buildUpon(): Builder = Builder()
        .scheme(getScheme())
        .encodedAuthority(getEncodedAuthority())
        .encodedPath(getEncodedPath())
        .encodedQuery(getEncodedQuery())
        .encodedFragment(getEncodedFragment())

    open fun normalizeScheme(): Uri =
        getScheme()?.let { scheme -> parse(scheme.lowercase() + uriString.substring(scheme.length)) } ?: this

    override fun toString(): String = uriString
    override fun equals(other: Any?): Boolean = other is Uri && other.uriString == uriString
    override fun hashCode(): Int = uriString.hashCode()
    override fun compareTo(other: Uri): Int = uriString.compareTo(other.uriString)

    open class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String? = null
        private var query: String? = null
        private var fragment: String? = null

        open fun scheme(scheme: String?): Builder = apply { this.scheme = scheme }
        open fun authority(authority: String?): Builder = apply { this.authority = encode(authority, "@:[]") }
        open fun encodedAuthority(authority: String?): Builder = apply { this.authority = authority }
        open fun path(path: String?): Builder = apply { this.path = encode(path, "/") }
        open fun encodedPath(path: String?): Builder = apply { this.path = path }
        open fun appendPath(segment: String?): Builder = appendEncodedPath(encode(segment))
        open fun appendEncodedPath(segment: String?): Builder = apply {
            if (segment == null) return@apply
            val current = path.orEmpty()
            path = if (current.endsWith("/")) current + segment.removePrefix("/") else current + "/" + segment.removePrefix("/")
        }

        open fun query(query: String?): Builder = apply { this.query = encode(query, "=&") }
        open fun encodedQuery(query: String?): Builder = apply { this.query = query }
        open fun appendQueryParameter(key: String?, value: String?): Builder = apply {
            val pair = encode(key.orEmpty()) + "=" + encode(value.orEmpty())
            query = if (query.isNullOrEmpty()) pair else "$query&$pair"
        }

        open fun clearQuery(): Builder = apply { query = null }
        open fun fragment(fragment: String?): Builder = apply { this.fragment = encode(fragment) }
        open fun encodedFragment(fragment: String?): Builder = apply { this.fragment = fragment }

        open fun build(): Uri = parse(buildString {
            scheme?.let { append(it).append(':') }
            authority?.let { append("//").append(it) }
            path?.let {
                if (authority != null && it.isNotEmpty() && !it.startsWith("/")) append('/')
                append(it)
            }
            query?.let { append('?').append(it) }
            fragment?.let { append('#').append(it) }
        })

        override fun toString(): String = build().toString()
    }

    companion object {
        /** RFC 3986 appendix B */
        private val URI_REGEX = Regex("""^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?""", RegexOption.DOT_MATCHES_ALL)
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*"

        @JvmField
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String?): Uri = Uri(uriString ?: throw NullPointerException("uriString"))

        @JvmStatic
        fun fromFile(file: File?): Uri {
            val path = file!!.absolutePath.replace('\\', '/')
            return Uri("file://" + encode(if (path.startsWith("/")) path else "/$path", "/:"))
        }

        @JvmStatic
        fun fromParts(scheme: String?, ssp: String?, fragment: String?): Uri =
            Uri(scheme + ":" + encode(ssp, "/:@") + (fragment?.let { "#" + encode(it) } ?: ""))

        @JvmStatic
        fun withAppendedPath(baseUri: Uri?, pathSegment: String?): Uri = baseUri!!.buildUpon().appendEncodedPath(pathSegment).build()

        @JvmStatic
        fun encode(s: String?): String? = encode(s, null)

        /** Percent-encodes everything but letters, digits, "_-!.~'()*" and the allowed characters */
        @JvmStatic
        fun encode(s: String?, allow: String?): String? {
            if (s == null) return null
            val out = StringBuilder()
            for (b in s.toByteArray(StandardCharsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (b >= 0 && (c in UNRESERVED || (allow != null && c in allow))) out.append(c)
                else out.append('%').append("%02X".format(b.toInt() and 0xff))
            }
            return out.toString()
        }

        @JvmStatic
        fun decode(s: String?): String? = s?.let { percentDecode(it, plusAsSpace = false) }

        /** Like Android's UriCodec, an invalid escape is kept as it is rather than failing */
        private fun percentDecode(s: String, plusAsSpace: Boolean): String {
            if ('%' !in s && !(plusAsSpace && '+' in s)) return s
            val out = StringBuilder()
            val bytes = ByteArrayOutputStream()
            fun flush() {
                if (bytes.size() > 0) out.append(bytes.toByteArray().toString(StandardCharsets.UTF_8)).also { bytes.reset() }
            }
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).takeIf { it.all(::isHexDigit) }?.toInt(16) else null
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                } else {
                    flush()
                    out.append(if (c == '+' && plusAsSpace) ' ' else c)
                    i++
                }
            }
            flush()
            return out.toString()
        }

        private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
