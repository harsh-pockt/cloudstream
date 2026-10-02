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
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
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
    /** The file an external track was added from */
    val externalFilename: String? = null,
) {
    val label: String get() = listOfNotNull(title, lang).distinct().joinToString(" · ").ifEmpty { "Track $id" }
}

data class MpvStatus(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val tracks: List<MpvTrack> = emptyList(),
    /** 0 to 100 */
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    /** Seconds the subtitles are shown later, negative for earlier */
    val subDelay: Double = 0.0,
)

/** A subtitle file to show with a link, added again whenever the link changes */
data class MpvSubtitle(val file: File, val title: String, val select: Boolean)

sealed interface MpvEvent {
    /** F or a double click inside the video */
    data object ToggleFullscreen : MpvEvent
    /** A single click inside the video */
    data object Click : MpvEvent
    data object Ended : MpvEvent
    /** The file could not be played, for example a refused or broken link */
    data class Failed(val url: String, val message: String) : MpvEvent
}

/**
 * One mpv player drawing into [canvas]. It starts when the canvas is shown and shuts down when the
 * canvas is removed, before its window is destroyed. Calls made before it started are run then.
 * The app draws the controls; the mouse over the video is handled here and reported as [events].
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
    /** Whether the current file finished loading. Subtitles can only be added to a loaded file */
    private var fileLoaded = false
    private val afterLoad = mutableListOf<() -> Unit>()

    private val _events = Channel<MpvEvent>(Channel.UNLIMITED)
    val events: Flow<MpvEvent> = _events.receiveAsFlow()

    /** Called on every mouse move over the video, from the AWT thread */
    @Volatile var onMouseMove: (() -> Unit)? = null

    // A double click toggles full screen, so a single click waits a moment to be sure it is not one
    private val singleClick = javax.swing.Timer(
        ((Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 300).coerceAtMost(300),
    ) { _events.trySend(MpvEvent.Click) }.apply { isRepeats = false }

    val canvas: Canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            start(Native.getComponentID(this))
        }

        override fun removeNotify() {
            singleClick.stop()
            shutdown()
            super.removeNotify()
        }
    }.apply {
        background = Color.BLACK
        // Keys stay with the app, which handles them the same inside and outside the video
        isFocusable = false
        // Embedded mpv takes no input itself: its window is disabled, so the mouse reaches this canvas
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                onMouseMove?.invoke()
            }

            override fun mouseDragged(e: MouseEvent) = mouseMoved(e)

            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                when {
                    e.clickCount == 1 -> singleClick.restart()
                    e.clickCount == 2 -> {
                        singleClick.stop()
                        _events.trySend(MpvEvent.ToggleFullscreen)
                    }
                }
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                // The wheel changes the volume, as in most desktop players
                changeVolume(if (e.wheelRotation < 0) 5 else -5)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    private fun start(windowId: Long) = synchronized(this) {
        if (started || closed) return
        started = true
        option("wid", windowId.toString())
        // The app draws its own controls. mpv's would not work anyway: embedded, it gets no mouse input
        option("osc", "no")
        option("hwdec", "auto-safe")
        // Stay on the last frame at the end instead of closing the file
        option("keep-open", "yes")
        option("idle", "yes")
        option("force-window", "yes")
        option("sub-auto", "no")
        option("drag-and-drop", "no")
        check("initialize", lib.mpv_initialize(ctx))
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
                    MPV_EVENT_FILE_LOADED -> synchronized(this) {
                        fileLoaded = true
                        afterLoad.forEach { it() }
                        afterLoad.clear()
                    }
                    MPV_EVENT_END_FILE -> {
                        val data = event.getPointer(16)
                        val reason = data.getInt(0)
                        val url = currentUrl ?: continue
                        when (reason) {
                            END_FILE_REASON_EOF -> _events.trySend(MpvEvent.Ended)
                            END_FILE_REASON_ERROR -> _events.trySend(MpvEvent.Failed(url, lib.mpv_error_string(data.getInt(4))))
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
    fun play(link: ExtractorLink, startSeconds: Double? = null, subtitles: List<MpvSubtitle> = emptyList()) = whenStarted {
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
        fileLoaded = false
        afterLoad.clear()
        command("loadfile", link.url, "replace")
        link.audioTracks.forEach { command("audio-add", it.url, "auto") }
        // A new file drops the subtitles added to the last one
        subtitles.forEach { addSubtitle(it.file, it.title, it.select) }
    }

    /** Runs once the current file is loaded */
    private fun whenLoaded(action: () -> Unit) = whenStarted {
        if (fileLoaded) action() else afterLoad += action
    }

    /** Adds a subtitle file, shown when [select] is true */
    fun addSubtitle(file: File, title: String, select: Boolean = true) = whenLoaded {
        command("sub-add", file.absolutePath, if (select) "select" else "auto", title)
    }

    /** Hides subtitles once the file is loaded, for a new link after the user turned them off */
    fun hideSubtitlesWhenLoaded() = whenLoaded { command("set", "sid", "no") }

    /** Shows a subtitle track, or hides subtitles when [id] is null */
    fun selectSubtitle(id: Int?) = whenStarted { command("set", "sid", id?.toString() ?: "no") }

    fun selectAudio(id: Int) = whenStarted { command("set", "aid", id.toString()) }

    fun togglePause() = whenStarted { command("cycle", "pause") }

    fun seek(seconds: Double) = whenStarted { command("seek", seconds.toString(), "relative") }

    fun seekTo(seconds: Double) = whenStarted { command("seek", seconds.toString(), "absolute") }

    fun changeVolume(delta: Int) = whenStarted { command("add", "volume", delta.toString()) }

    fun setVolume(volume: Int) = whenStarted { command("set", "volume", volume.coerceIn(0, 100).toString()) }

    fun toggleMute() = whenStarted { command("cycle", "mute") }

    fun setSpeed(speed: Double) = whenStarted { command("set", "speed", speed.toString()) }

    fun changeSubDelay(seconds: Double) = whenStarted { command("add", "sub-delay", seconds.toString()) }

    fun resetSubDelay() = whenStarted { command("set", "sub-delay", "0") }

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
                externalFilename = property("${prefix}external-filename"),
            )
        }
        return MpvStatus(
            position = property("time-pos")?.toDoubleOrNull() ?: 0.0,
            duration = property("duration")?.toDoubleOrNull() ?: 0.0,
            paused = property("pause") == "yes",
            buffering = property("paused-for-cache") == "yes",
            tracks = tracks,
            volume = property("volume")?.toDoubleOrNull()?.toInt() ?: 100,
            muted = property("mute") == "yes",
            speed = property("speed")?.toDoubleOrNull() ?: 1.0,
            subDelay = property("sub-delay")?.toDoubleOrNull() ?: 0.0,
        )
    }

    private companion object {
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val END_FILE_REASON_EOF = 0
        const val END_FILE_REASON_ERROR = 4
    }
}
