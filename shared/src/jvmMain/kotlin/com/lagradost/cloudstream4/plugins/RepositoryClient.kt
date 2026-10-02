package com.lagradost.cloudstream4.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A repository's repo.json, see RepositoryManager.Repository in the Android app */
data class Repository(
    val url: String,
    val name: String,
    val description: String?,
    val pluginLists: List<String>,
)

/** One entry in a repository's plugin list, see SitePlugin in the Android app */
data class RepoPlugin(
    val internalName: String,
    val name: String,
    val version: Int,
    /** 0 means the provider is down, see PROVIDER_STATUS_DOWN */
    val status: Int,
    val description: String?,
    val authors: List<String>,
    val language: String?,
    val tvTypes: List<String>,
    val iconUrl: String?,
    /** Only set when the plugin is built with `isCrossPlatform = true`, which desktop needs */
    val jarUrl: String?,
    /** "sha256-<hex>" */
    val jarHash: String?,
    val jarFileSize: Long?,
) {
    val supportsDesktop: Boolean get() = jarUrl != null
}

class PluginDownloadException(message: String) : Exception(message)

/**
 * Downloads repositories and plugin jars.
 *
 * Uses OkHttp rather than java.net.http because OkHttp tries every address a host resolves to and
 * races IPv6 against IPv4. The JDK clients only try the first address, so a single unreachable
 * GitHub address (seen on some ISPs) made every download time out.
 */
class RepositoryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    /** Where short codes such as "megarepo" are looked up, and "!code" ones. Changed by tests only */
    private val shortLinkHost: String = "https://cutt.ly/",
    private val bangShortLinkHost: String = "https://py.md/",
) {
    /**
     * Turns what the user typed into a repository URL, with the same rules as the Android app's
     * RepositoryManager.parseRepoUrl: cloudstreamrepo:// and https://cs.repo/ links lose their prefix,
     * a URL is kept, and a short code is looked up at cutt.ly (or py.md for "!code"), which redirects
     * to the repository. Returns null when nothing is found.
     */
    suspend fun resolveRepositoryUrl(input: String): String? {
        val text = input.trim()
        // Before the URL check, which https://cs.repo/ links would otherwise pass unchanged
        if (text.contains(DEEP_LINK_REGEX)) {
            val rest = text.replace(DEEP_LINK_REGEX, "")
            return if (rest.contains(URL_REGEX)) rest else "https://$rest"
        }
        if (text.contains(URL_REGEX)) return text
        if (!text.matches(SHORT_CODE_REGEX)) return null
        val (host, code) = if (text.startsWith("!")) bangShortLinkHost to text.removePrefix("!") else shortLinkHost to text
        val location = withContext(Dispatchers.IO) {
            val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            noRedirects.newCall(Request.Builder().url(host + code).build()).execute().use { it.header("Location") }
        } ?: return null
        // The short link sites send unknown codes to their 404 page or their home page
        val base = host.removeSuffix("/")
        if (location.startsWith("$base/404") || location.removeSuffix("/") == base) return null
        return location
    }

    /**
     * The repositories in CloudStream's community list, the same list the MegaRepo plugin adds on
     * Android. Entries are either a URL or an object with a "url".
     */
    suspend fun fetchCommunityRepositories(listUrl: String = COMMUNITY_REPOSITORIES): List<String> =
        Json.parseToJsonElement(getText(listUrl)).jsonArray.mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.takeIf { it.isString }?.content
                is JsonObject -> entry.string("url")
                else -> null
            }
        }.distinct()

    suspend fun fetchRepository(url: String): Repository {
        val json = Json.parseToJsonElement(getText(url))
        // Some people share the plugin list directly instead of repo.json
        if (json is JsonArray) return Repository(url, url, null, listOf(url))
        val obj = json.jsonObject
        return Repository(
            url = url,
            name = obj.string("name") ?: url,
            description = obj.string("description"),
            pluginLists = obj["pluginLists"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        )
    }

    /** Every plugin in all of the repository's plugin lists. A list that fails to load is skipped */
    suspend fun fetchPlugins(repository: Repository): List<RepoPlugin> = repository.pluginLists.flatMap { list ->
        runCatching { parsePluginList(getText(list)) }
            .onFailure { println("WARNING RepositoryClient: Could not load plugin list $list: $it") }
            .getOrDefault(emptyList())
    }

    /** Downloads the plugin jar and checks its size and hash against the repository */
    suspend fun downloadJar(plugin: RepoPlugin): ByteArray {
        val url = plugin.jarUrl ?: throw PluginDownloadException("${plugin.name} has no desktop version")
        val bytes = getBytes(url)
        plugin.jarFileSize?.let { expected ->
            if (bytes.size.toLong() != expected) {
                throw PluginDownloadException("${plugin.name}: expected $expected bytes, got ${bytes.size}")
            }
        }
        plugin.jarHash?.let { expected ->
            val actual = "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw PluginDownloadException("${plugin.name}: the download does not match the repository's hash")
            }
        }
        return bytes
    }

    private suspend fun getText(url: String): String = getBytes(url).decodeToString()

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw PluginDownloadException("HTTP ${response.code} for $url")
            response.body.bytes()
        }
    }

    companion object {
        const val OFFICIAL_REPOSITORY = "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json"

        /** The community repository list, see https://github.com/recloudstream/cs-repos */
        const val COMMUNITY_REPOSITORIES = "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"

        /**
         * The internal name of MegaRepo's only plugin. On Android it adds every community repository and
         * nothing else, through app internals desktop does not have, so desktop does that itself instead.
         */
        const val MEGA_REPO_PLUGIN = "MegaProvider"

        private val URL_REGEX = "^https?://".toRegex()
        private val DEEP_LINK_REGEX = """^(cloudstreamrepo://|https://cs\.repo/\??)""".toRegex()
        private val SHORT_CODE_REGEX = "^[a-zA-Z0-9!_-]+$".toRegex()

        internal fun parsePluginList(text: String): List<RepoPlugin> =
            Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                RepoPlugin(
                    internalName = obj.string("internalName") ?: return@mapNotNull null,
                    name = obj.string("name") ?: obj.string("internalName")!!,
                    version = obj.primitive("version")?.intOrNull ?: 0,
                    status = obj.primitive("status")?.intOrNull ?: 1,
                    description = obj.string("description"),
                    authors = obj["authors"].strings(),
                    language = obj.string("language"),
                    tvTypes = obj["tvTypes"].strings(),
                    iconUrl = obj.string("iconUrl"),
                    jarUrl = obj.string("jarUrl"),
                    jarHash = obj.string("jarHash"),
                    jarFileSize = obj.primitive("jarFileSize")?.longOrNull,
                )
            }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

        private fun JsonElement?.strings(): List<String> =
            (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    }
}
