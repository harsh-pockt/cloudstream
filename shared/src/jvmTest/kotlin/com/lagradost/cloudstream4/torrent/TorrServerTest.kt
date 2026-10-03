package com.lagradost.cloudstream4.torrent

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Downloads TorrServer (63 MB, kept in the build folder) and streams a public torrent, so it only
 * runs with -Dcloudstream.torrentTests=true (gradle: -PtorrentTests) on Windows.
 */
class TorrServerTest {
    private val enabled = System.getProperty("cloudstream.torrentTests") == "true" && TorrServer.supported
    private val server = TorrServer(File(System.getProperty("cloudstream.home"), "torrserver"))

    @AfterTest
    fun stop() = server.stop()

    @Test
    fun streamsAMagnetLink() = runBlocking {
        if (!enabled) return@runBlocking
        server.install()
        assertTrue(server.installed)
        // Big Buck Bunny (CC BY 3.0), from WebTorrent's examples
        val magnet = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fexplodie.org%3A6969" +
            "&tr=wss%3A%2F%2Ftracker.btorrent.xyz&tr=wss%3A%2F%2Ftracker.openwebtorrent.com" +
            "&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F"
        val stream = server.stream(newExtractorLink("Test", "Big Buck Bunny", magnet, ExtractorLinkType.MAGNET))
        println("Streaming ${stream.fileName} from ${stream.url}")
        assertTrue(stream.fileName.endsWith(".mp4"), stream.fileName)
        val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(stream.url).header("Range", "bytes=0-1048575").build()).execute().use { response ->
            assertEquals(206, response.code)
            val bytes = response.body.bytes()
            assertEquals(1_048_576, bytes.size)
            // An MP4 starts with an "ftyp" box
            assertEquals("ftyp", String(bytes, 4, 4))
        }
        server.drop(stream.hash)
    }
}
