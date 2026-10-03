package com.lagradost.cloudstream4.torrent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A torrent being streamed: where the player reads it, and what to drop when done */
data class TorrentStream(val url: String, val hash: String, val fileName: String)

/**
 * Streams torrents and magnet links, as the Android app does with its built-in TorrServer. Desktop
 * runs TorrServer's own Windows program (GPL-3.0, https://github.com/YouROK/TorrServer), downloaded
 * once on first use and checked against its published SHA-256. It listens on this computer only,
 * and the player reads the torrent from it as an HTTP stream while it downloads.
 */
class TorrServer(
    private val dir: File = AppDirs.cache.resolve("torrserver").toFile(),
    private val download: DownloadSource = DownloadSource.WINDOWS_AMD64,
) {
    /** A pinned release of TorrServer, with the digest GitHub shows for it */
    data class DownloadSource(val url: String, val sha256: String, val fileName: String) {
        companion object {
            private const val VERSION = "MatriX.145.1"
            val WINDOWS_AMD64 = DownloadSource(
                "https://github.com/YouROK/TorrServer/releases/download/$VERSION/TorrServer-windows-amd64.exe",
                "d8bf93aba0521f2450436d4ede02c3b6e44a37b52c386aa9e6206daa8e5551ed",
                "TorrServer-$VERSION.exe",
            )
        }
    }

    private val json = jacksonObjectMapper()
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var base: String? = null

    val executable: File get() = dir.resolve(download.fileName)

    /** TorrServer is downloaded already */
    val installed: Boolean get() = executable.isFile

    /** Downloads TorrServer, reporting bytes done and the full size */
    suspend fun install(progress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        if (installed) return@withContext
        dir.mkdirs()
        val part = dir.resolve("${download.fileName}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(download.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TorrServer could not be downloaded: HTTP ${response.code}")
            val total = response.body.contentLength()
            var done = 0L
            part.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        progress(done, total)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != download.sha256) {
            part.delete()
            throw IOException("TorrServer's download did not match its published checksum")
        }
        if (!part.renameTo(executable)) throw IOException("TorrServer could not be saved to $dir")
    }

    /**
     * Adds a torrent or magnet link and returns where the player can stream it: the file the link
     * names with `index=`, otherwise the largest video. Waits for the torrent's file list, which
     * comes from its peers.
     */
    suspend fun stream(link: ExtractorLink): TorrentStream = withContext(Dispatchers.IO) {
        val server = start()
        val added = post(server, mapOf("action" to "add", "link" to link.url, "title" to link.name, "save_to_db" to false))
        val hash = added.path("hash").asText().ifEmpty { throw IOException("TorrServer did not take the link") }
        val files = withTimeoutOrNull(METADATA_TIMEOUT_MS) { files(server, hash) } ?: run {
            drop(hash)
            throw IOException("No peers sent the torrent's file list within a minute")
        }
        val wanted = Regex("""[?&]index=(\d+)""").find(link.url)?.groupValues?.get(1)?.toIntOrNull()
        val file = files.firstOrNull { wanted != null && it.path("id").asInt() == wanted }
            ?: files.filter { it.path("path").asText().substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS }.maxByOrNull { it.path("length").asLong() }
            ?: files.maxBy { it.path("length").asLong() }
        val path = file.path("path").asText()
        val name = URLEncoder.encode(path.substringAfterLast('/'), "UTF-8").replace("+", "%20")
        TorrentStream("$server/stream/$name?link=$hash&index=${file.path("id").asInt()}&play", hash, path)
    }

    /** The torrent's files, once its peers sent the list */
    private suspend fun files(server: String, hash: String): List<JsonNode> {
        while (true) {
            val status = post(server, mapOf("action" to "get", "hash" to hash))
            status.path("file_stats").takeIf { it.isArray && it.size() > 0 }?.let { return it.toList() }
            delay(500)
        }
    }

    /** Stops downloading a torrent and forgets it */
    suspend fun drop(hash: String) = withContext(Dispatchers.IO) {
        val server = base ?: return@withContext
        runCatching { post(server, mapOf("action" to "drop", "hash" to hash)) }
        runCatching { post(server, mapOf("action" to "rem", "hash" to hash)) }
    }

    private fun post(server: String, body: Map<String, Any?>): JsonNode {
        val request = Request.Builder().url("$server/torrents")
            .post(json.writeValueAsString(body).toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TorrServer: HTTP ${response.code} for ${body["action"]}")
            val text = response.body.string()
            return if (text.isBlank()) json.createObjectNode() else json.readTree(text)
        }
    }

    /** Starts TorrServer on a free port, listening on this computer only */
    private suspend fun start(): String = lock.withLock {
        val running = base
        if (running != null && process?.isAlive == true) return running
        if (!installed) throw IOException("TorrServer is not installed")
        val port = ServerSocket(0).use { it.localPort }
        val data = dir.resolve("data").apply { mkdirs() }
        val started = ProcessBuilder(executable.absolutePath, "-p", port.toString(), "-i", "127.0.0.1", "-d", data.absolutePath)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("torrserver.log"))
            .start()
        process = started
        val server = "http://127.0.0.1:$port"
        val up = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (started.isAlive) {
                val echo = runCatching { http.newCall(Request.Builder().url("$server/echo").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
                if (echo) return@withTimeoutOrNull true
                delay(200)
            }
            false
        } ?: false
        if (!up) {
            started.destroyForcibly()
            throw IOException("TorrServer did not start, see ${dir.resolve("torrserver.log")}")
        }
        base = server
        println("INFO TorrServer: started on $server")
        server
    }

    /** Stops TorrServer, also when the app exits */
    fun stop() {
        val running = process ?: return
        process = null
        base = null
        running.descendants().forEach { it.destroy() }
        running.destroy()
        if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
    }

    companion object {
        private const val METADATA_TIMEOUT_MS = 60_000L
        private const val START_TIMEOUT_MS = 20_000L
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "wmv", "flv", "mpg", "mpeg")

        fun isTorrent(link: ExtractorLink) = link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET

        /** Only on Windows for now: TorrServer publishes a program per system */
        val supported: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")

        /** Whether the user agreed to stream torrents in this session, as the Android app asks */
        @Volatile var accepted = false

        val instance: TorrServer by lazy {
            TorrServer().also { server -> Runtime.getRuntime().addShutdownHook(Thread { server.stop() }) }
        }
    }
}
