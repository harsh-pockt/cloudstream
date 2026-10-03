package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.cloudstream4.network.DesktopHttp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How far a file is: bytes written, and the full size when known */
fun interface DownloadProgress {
    /** [segmentsDone] is set for HLS, after each segment is on disk */
    fun update(bytes: Long, total: Long, segmentsDone: Int?)
}

/**
 * Writes one link to a file: a plain video, carrying on where an earlier attempt stopped when the
 * server allows it, or an HLS stream, its segments joined into one file as the Android app does.
 */
object Downloader {
    /** Without the HTTP cache, which must not hold whole videos */
    private val http: OkHttpClient by lazy {
        DesktopHttp.client.newBuilder().cache(null).readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun canDownload(link: ExtractorLink) = link.type == ExtractorLinkType.VIDEO || link.type == ExtractorLinkType.M3U8

    /** The link's headers with a user agent and its referer, the link's own winning */
    fun headers(link: ExtractorLink): Map<String, String> {
        val headers = link.headers.toMutableMap()
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) headers["User-Agent"] = USER_AGENT
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) headers["Referer"] = link.referer
        return headers
    }

    /**
     * Downloads [link] to [file], carrying on from [resume] when an earlier attempt with the same link
     * got that far. Throws when the link cannot be downloaded.
     */
    suspend fun download(link: ExtractorLink, file: File, resume: Resume?, connections: Int, progress: DownloadProgress) {
        file.parentFile?.mkdirs()
        if (link.type == ExtractorLinkType.M3U8) hls(link, file, resume, connections, progress)
        else video(link, file, if (resume != null) file.length() else 0, progress)
    }

    /** How far an earlier attempt got: for HLS, the segments done and the file's length after them */
    data class Resume(val segments: Int, val bytes: Long)

    private suspend fun video(link: ExtractorLink, file: File, resumeFrom: Long, progress: DownloadProgress) = withContext(Dispatchers.IO) {
        val start = if (resumeFrom > 0 && file.length() >= resumeFrom) resumeFrom else 0
        val request = Request.Builder().url(link.url.replace(" ", "%20")).apply {
            headers(link).forEach { (key, value) -> header(key, value) }
            if (start > 0) header("Range", "bytes=$start-")
        }.build()
        val call = http.newCall(request)
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val type = response.header("Content-Type").orEmpty().lowercase()
                // A web page where the video should be: an expired or protected link
                if (type.startsWith("text/") || type.contains("json")) throw IOException("The link gave a web page, not a video")
                // 206 carries on, a 200 sends the whole file again
                val from = if (response.code == 206) start else 0
                val length = response.body.contentLength()
                val total = if (length > 0) from + length else -1
                FileOutputStream(file, from > 0).use { out ->
                    var written = from
                    var reported = 0L
                    val buffer = ByteArray(256 * 1024)
                    response.body.byteStream().use { input ->
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            val now = System.nanoTime()
                            if (now - reported > 500_000_000) {
                                reported = now
                                progress.update(written, total, null)
                            }
                        }
                    }
                    progress.update(written, if (total > 0) total else written, null)
                    if (total > 0 && written < total) throw IOException("The connection closed early")
                }
            }
        } finally {
            cancel?.dispose()
        }
    }

    private suspend fun hls(link: ExtractorLink, file: File, resume: Resume?, connections: Int, progress: DownloadProgress) {
        val stream = M3u8Helper2.hslLazy(M3u8Helper.M3u8Stream(link.url, link.quality, headers(link)), selectBest = true, requireAudio = true)
        val size = stream.size
        if (size == 0) throw IOException("The stream has no segments")
        // Only when the file still holds all the segments saved, without what came after them
        val first = resume?.takeIf { file.length() >= it.bytes }?.segments?.coerceIn(0, size) ?: 0
        withContext(Dispatchers.IO) {
            if (first > 0) java.io.RandomAccessFile(file, "rw").use { it.setLength(resume!!.bytes) }
            FileOutputStream(file, first > 0).use { out ->
                var written = file.length()
                // A few segments load ahead while one is written, in order
                val ahead = connections.coerceIn(1, 8)
                coroutineScope {
                    val pending = ArrayDeque<Deferred<ByteArray?>>()
                    var next = first
                    fun fill() {
                        while (pending.size < ahead && next < size) {
                            val index = next++
                            pending.addLast(async { stream.resolveLinkSafe(index) })
                        }
                    }
                    fill()
                    var index = first
                    while (pending.isNotEmpty()) {
                        val bytes = pending.removeFirst().await() ?: throw IOException("Segment ${index + 1} of $size could not be loaded")
                        out.write(bytes)
                        out.flush()
                        written += bytes.size
                        index++
                        // The size is known once all segments are in, until then it is guessed from those so far
                        progress.update(written, if (index == size) written else written / index * size, index)
                        fill()
                    }
                }
            }
        }
    }
}
