package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.awt.Canvas
import java.awt.Color
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** The libmpv functions the player uses, see mpv/client.h */
@Suppress("FunctionName")
internal interface MpvLibrary : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_terminate_destroy(ctx: Pointer)
}

/** Finds and loads libmpv-2.dll once */
object Mpv {
    private const val FILE = "libmpv-2.dll"

    /** Why libmpv could not be loaded, or null when it loaded */
    var loadError: String? = null
        private set

    internal val library: MpvLibrary? by lazy {
        // The installed app keeps it in its resources folder. When run from Gradle it is in the build folder
        val candidates = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it, FILE) },
            File("build/libmpv/windows", FILE),
            File("desktopApp/build/libmpv/windows", FILE),
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            loadError = "$FILE was not found. Looked in: ${candidates.joinToString { it.absolutePath }}"
            println("ERROR Mpv: $loadError")
            return@lazy null
        }
        runCatching {
            Native.load(file.absolutePath, MpvLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }.onSuccess {
            println("INFO Mpv: Loaded ${file.absolutePath}")
        }.onFailure {
            loadError = "$FILE could not be loaded: ${it.message}"
            println("ERROR Mpv: $loadError")
        }.getOrNull()
    }
}

data class MpvTrack(
    val id: Int,
    /** "video", "audio" or "sub" */
    val type: String,
    val title: String?,
    val lang: String?,
    val selected: Boolean,
    val external: Boolean,
) {
    val label: String get() = listOfNotNull(title, lang).distinct().joinToString(" · ").ifEmpty { "Track $id" }
}

data class MpvStatus(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val tracks: List<MpvTrack> = emptyList(),
)

sealed interface MpvEvent {
    /** Escape or Backspace was pressed inside the video */
    data object Back : MpvEvent
    /** F or a double click inside the video */
    data object ToggleFullscreen : MpvEvent
    data object Ended : MpvEvent
    /** The file could not be played, for example a refused or broken link */
    data class Failed(val url: String, val message: String) : MpvEvent
}

/**
 * One mpv player drawing into [canvas]. It starts when the canvas is shown and shuts down when the
 * canvas is removed, before its window is destroyed. Calls made before it started are run then.
 * mpv's own on-screen controller gives the seek bar and buttons, and its default keys work.
 */
class MpvPlayer internal constructor(private val lib: MpvLibrary) {
    private val ctx: Pointer = lib.mpv_create() ?: error("mpv_create failed")
    private var started = false
    private var closed = false
    private val pending = mutableListOf<() -> Unit>()
    private var eventThread: Thread? = null
    private val stopped = CountDownLatch(1)
    /** The url being played, so a failure can be reported for the right link */
    @Volatile private var currentUrl: String? = null

    private val _events = Channel<MpvEvent>(Channel.UNLIMITED)
    val events: Flow<MpvEvent> = _events.receiveAsFlow()

    val canvas: Canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            start(Native.getComponentID(this))
        }

        override fun removeNotify() {
            shutdown()
            super.removeNotify()
        }
    }.apply { background = Color.BLACK }

    private fun start(windowId: Long) = synchronized(this) {
        if (started || closed) return
        started = true
        option("wid", windowId.toString())
        option("osc", "yes")
        option("input-default-bindings", "yes")
        option("input-vo-keyboard", "yes")
        option("hwdec", "auto-safe")
        // Stay on the last frame at the end instead of closing the file
        option("keep-open", "yes")
        option("idle", "yes")
        option("force-window", "yes")
        option("sub-auto", "no")
        // Between files mpv would show its own logo and invite dropping files, neither belongs here
        option("script-opts", "osc-idlescreen=no")
        option("drag-and-drop", "no")
        check("initialize", lib.mpv_initialize(ctx))
        // Keys inside the video go to mpv, so the app's own keys are rebound to messages it sends back
        for ((key, message) in listOf("ESC" to "cs-back", "BS" to "cs-back", "f" to "cs-fullscreen", "MBTN_LEFT_DBL" to "cs-fullscreen")) {
            command("keybind", key, "script-message $message")
        }
        eventThread = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
        pending.forEach { it() }
        pending.clear()
    }

    /** Runs now if mpv started, otherwise once it has */
    private fun whenStarted(action: () -> Unit) = synchronized(this) {
        when {
            closed -> Unit
            started -> action()
            else -> pending += action
        }
    }

    private fun shutdown() {
        synchronized(this) {
            if (closed) return
            closed = true
            if (!started) {
                lib.mpv_terminate_destroy(ctx)
                return
            }
            command("quit")
        }
        // The event thread destroys the handle; wait so mpv lets go of the window before it is destroyed
        if (!stopped.await(5, TimeUnit.SECONDS)) println("ERROR MpvPlayer: mpv did not shut down in time")
    }

    private fun eventLoop() {
        try {
            while (true) {
                val event = lib.mpv_wait_event(ctx, -1.0)
                when (event.getInt(0)) {
                    MPV_EVENT_SHUTDOWN -> return
                    MPV_EVENT_END_FILE -> {
                        val data = event.getPointer(16)
                        val reason = data.getInt(0)
                        val url = currentUrl ?: continue
                        when (reason) {
                            END_FILE_REASON_EOF -> _events.trySend(MpvEvent.Ended)
                            END_FILE_REASON_ERROR -> _events.trySend(MpvEvent.Failed(url, lib.mpv_error_string(data.getInt(4))))
                        }
                    }

                    MPV_EVENT_CLIENT_MESSAGE -> {
                        val data = event.getPointer(16)
                        if (data.getInt(0) < 1) continue
                        when (data.getPointer(8).getPointer(0).getString(0)) {
                            "cs-back" -> _events.trySend(MpvEvent.Back)
                            "cs-fullscreen" -> _events.trySend(MpvEvent.ToggleFullscreen)
                        }
                    }
                }
            }
        } finally {
            lib.mpv_terminate_destroy(ctx)
            stopped.countDown()
        }
    }

    private fun option(name: String, value: String) = check(name, lib.mpv_set_option_string(ctx, name, value))

    private fun command(vararg args: String): Int = lib.mpv_command(ctx, arrayOf(*args, null)).also { check(args.first(), it) }

    private fun check(what: String, code: Int) {
        if (code < 0) println("ERROR MpvPlayer: $what failed: ${lib.mpv_error_string(code)}")
    }

    private fun property(name: String): String? {
        if (!started || closed) return null
        val value = lib.mpv_get_property_string(ctx, name) ?: return null
        return value.getString(0, "UTF-8").also { lib.mpv_free(value) }
    }

    /**
     * Plays a link with the headers the provider gave for it. The referer and user agent have their
     * own mpv options, every other header is sent as is on every request, playlists and segments included.
     */
    fun play(link: ExtractorLink, startSeconds: Double? = null) = whenStarted {
        val headers = link.headers.toMutableMap()
        fun take(name: String) = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
        val userAgent = take("User-Agent") ?: USER_AGENT
        val headerReferer = take("Referer")
        val referer = link.referer.ifBlank { headerReferer.orEmpty() }

        currentUrl = link.url
        command("set", "user-agent", userAgent)
        command("set", "referrer", referer)
        // Appended one by one, so a comma inside a header value is never taken as a separator
        command("change-list", "http-header-fields", "clr", "")
        headers.forEach { (key, value) -> command("change-list", "http-header-fields", "append", "$key: $value") }
        command("set", "start", startSeconds?.let { "+$it" } ?: "none")
        // keep-open pauses at the end of a file, which would carry over to the next one
        command("set", "pause", "no")
        command("loadfile", link.url, "replace")
        link.audioTracks.forEach { command("audio-add", it.url, "auto") }
    }

    /** Adds a subtitle file and shows it */
    fun addSubtitle(file: File, title: String) = whenStarted {
        command("sub-add", file.absolutePath, "select", title)
    }

    /** Shows a subtitle track, or hides subtitles when [id] is null */
    fun selectSubtitle(id: Int?) = whenStarted { command("set", "sid", id?.toString() ?: "no") }

    fun selectAudio(id: Int) = whenStarted { command("set", "aid", id.toString()) }

    fun togglePause() = whenStarted { command("cycle", "pause") }

    fun seek(seconds: Double) = whenStarted { command("seek", seconds.toString(), "relative") }

    fun changeVolume(delta: Int) = whenStarted { command("add", "volume", delta.toString()) }

    fun status(): MpvStatus {
        if (!started || closed) return MpvStatus()
        val count = property("track-list/count")?.toIntOrNull() ?: 0
        val tracks = (0 until count).mapNotNull { i ->
            val prefix = "track-list/$i/"
            MpvTrack(
                id = property("${prefix}id")?.toIntOrNull() ?: return@mapNotNull null,
                type = property("${prefix}type") ?: return@mapNotNull null,
                title = property("${prefix}title"),
                lang = property("${prefix}lang"),
                selected = property("${prefix}selected") == "yes",
                external = property("${prefix}external") == "yes",
            )
        }
        return MpvStatus(
            position = property("time-pos")?.toDoubleOrNull() ?: 0.0,
            duration = property("duration")?.toDoubleOrNull() ?: 0.0,
            paused = property("pause") == "yes",
            buffering = property("paused-for-cache") == "yes",
            tracks = tracks,
        )
    }

    private companion object {
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_CLIENT_MESSAGE = 16
        const val END_FILE_REASON_EOF = 0
        const val END_FILE_REASON_ERROR = 4
    }
}
