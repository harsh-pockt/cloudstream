package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream4.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream4.network.DesktopHttp
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads provider subtitles to the cache folder, so mpv opens a local file. Each subtitle can have
 * its own headers, which mpv could only send by changing the headers of the video too.
 */
object Subtitles {
    private val http get() = DesktopHttp.client
    private val dir: File get() = AppDirs.cache.resolve("subtitles").toFile()

    suspend fun download(subtitle: SubtitleFile): File = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-1").digest(subtitle.url.toByteArray()).joinToString("") { "%02x".format(it) }
        // mpv reads the format from the content, the extension only helps when it is known
        val extension = subtitle.url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "dfxp") } ?: "sub"
        val file = dir.resolve("$name.$extension")
        if (file.isFile && file.length() > 0) return@withContext file

        dir.mkdirs()
        val request = Request.Builder().url(subtitle.url).apply {
            header("User-Agent", USER_AGENT)
            subtitle.headers?.forEach { (key, value) -> header(key, value) }
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${subtitle.url}")
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { response.body.byteStream().copyTo(it) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        file
    }
}
