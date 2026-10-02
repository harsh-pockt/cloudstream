package com.lagradost.cloudstream4.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.browse.Message
import com.lagradost.cloudstream4.browse.formatTime
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.arrow_back
import com.lagradost.cloudstream4.generated.resources.baseline_fullscreen_24
import com.lagradost.cloudstream4.generated.resources.baseline_fullscreen_exit_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_audiotrack_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_hd_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_replay_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_speed_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_subtitles_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_volume_mute_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_volume_up_24
import com.lagradost.cloudstream4.generated.resources.pause_24px
import com.lagradost.cloudstream4.generated.resources.play_arrow_24px
import com.lagradost.cloudstream4.library.DataStoreWatchStore
import com.lagradost.cloudstream4.library.saveProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.io.File
import kotlin.math.abs

/** Which subtitle the user picked, so it is shown again when the source changes */
private sealed interface SubtitleChoice {
    /** Nothing picked: mpv's own choice */
    data object Default : SubtitleChoice
    data object Off : SubtitleChoice
    /** An extension's subtitle, by its url */
    data class Extension(val url: String) : SubtitleChoice
}

/**
 * Plays a movie or episode. The links load in the background and the best one starts on its own.
 * The bar on top shows the title and source; the bar below plays, seeks, sets the volume and opens
 * the subtitle, audio, speed and source pickers. In full screen the bars show while the mouse moves and hide after a few seconds.
 * Space or K pauses, the left and right arrows or J and L go 10 s back and forward, the up and down
 * arrows change the volume, F toggles full screen, M pauses and hides the app. Escape closes an open panel, then
 * leaves full screen, then goes back. A click on the video closes an open panel, or pauses.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    viewModel: LinksViewModel,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    /** Hands the player's key handler to the window, null when the player closes */
    onKeys: (((KeyEvent) -> Boolean)?) -> Unit = {},
) {
    val lib = Mpv.library
    if (lib == null) {
        Message("The video player is missing", Mpv.loadError)
        return
    }
    val state by viewModel.state.collectAsState()
    val player = remember { MpvPlayer(lib) }
    var status by remember { mutableStateOf(MpvStatus()) }
    val scope = rememberCoroutineScope()
    var subtitleMessage by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    val currentFullscreen by rememberUpdatedState(onToggleFullscreen)
    val currentBack by rememberUpdatedState(onBack)

    // Extension subtitles downloaded so far, by url, and which one is shown
    val downloaded = remember { mutableStateMapOf<String, File>() }
    var subtitleChoice by remember { mutableStateOf<SubtitleChoice>(SubtitleChoice.Default) }
    var loadingSubtitle by remember { mutableStateOf<String?>(null) }

    // Where playback is, in ms: it starts where the user left off, and a new source carries on from there
    val tracking = viewModel.request.tracking
    val playedTo = remember { longArrayOf(tracking?.startPositionMs ?: 0, 0) }
    fun saveProgress(positionMs: Long = playedTo[0], durationMs: Long = playedTo[1]) {
        if (tracking != null) runCatching { DataStoreWatchStore.instance.saveProgress(tracking, positionMs, durationMs) }
            .onFailure { println("ERROR PlayerScreen: Could not save the playback position: $it") }
    }
    DisposableEffect(Unit) { onDispose { saveProgress() } }

    // Seconds really played on the current link, and the last position seen, to tell a link that
    // ends right after it starts (a broken file) from one watched to the end
    val playedOnLink = remember { doubleArrayOf(0.0, -1.0) }
    val currentState by rememberUpdatedState(state)

    // A new link was picked, by hand or because the last one failed. The subtitle picked stays on
    val selected = state.selectedLink
    LaunchedEffect(selected?.url) {
        if (selected == null) return@LaunchedEffect
        playedOnLink[0] = 0.0
        playedOnLink[1] = -1.0
        val choice = subtitleChoice
        val subtitles = state.subtitles.mapNotNull { sub ->
            downloaded[sub.url]?.let { MpvSubtitle(it, sub.lang, select = choice == SubtitleChoice.Extension(sub.url)) }
        }
        player.play(selected, playedTo[0].takeIf { it > 0 }?.let { it / 1000.0 }, subtitles)
        if (choice == SubtitleChoice.Off) player.hideSubtitlesWhenLoaded()
    }
    // In full screen the bars show on mouse moves and hide once the mouse rests, unless a panel is open
    var activity by remember { mutableStateOf(0L) }
    var barsShown by remember { mutableStateOf(true) }
    // Showing or hiding the bars resizes what is under the mouse, which reports a move too: only a
    // pointer that really moved on screen counts
    val lastPointer = remember { arrayOfNulls<java.awt.Point>(1) }
    fun pointerMoved() {
        val at = java.awt.MouseInfo.getPointerInfo()?.location ?: return
        if (at == lastPointer[0]) return
        lastPointer[0] = at
        activity = System.nanoTime()
    }
    DisposableEffect(player) {
        player.onMouseMove = ::pointerMoved
        onDispose { player.onMouseMove = null }
    }
    LaunchedEffect(fullscreen, activity, panel) {
        barsShown = true
        if (fullscreen && panel == null) {
            delay(3000)
            barsShown = false
        }
    }
    val showBars = !fullscreen || barsShown
    LaunchedEffect(player) {
        player.events.collect { event ->
            when (event) {
                MpvEvent.ToggleFullscreen -> currentFullscreen()
                MpvEvent.Back -> currentBack()
                MpvEvent.Click -> if (panel != null) panel = null else player.togglePause()
                is MpvEvent.Failed -> viewModel.onAction(LinksAction.Failed(event.url, event.message))
                MpvEvent.Ended -> {
                    val url = currentState.selected
                    // A file that stops within seconds of starting is broken: it must not count as watched
                    // unless it was started in its last half minute
                    val nearEnd = playedTo[1] > 0 && playedTo[0] >= playedTo[1] - 30_000
                    if (playedOnLink[0] < 10 && !nearEnd && url != null) viewModel.onAction(LinksAction.Failed(url, "Stopped right after it started"))
                    else saveProgress(playedTo[1], playedTo[1])
                }
            }
        }
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            status = player.status()
            val last = playedOnLink[1]
            if (last >= 0 && status.position > last && status.position - last < 2) playedOnLink[0] += status.position - last
            playedOnLink[1] = status.position
            // Only once the link really plays: a broken one can jump to its end, which is no progress
            if (status.duration > 0 && status.position > 0 && playedOnLink[0] >= 3) {
                playedTo[0] = (status.position * 1000).toLong()
                playedTo[1] = (status.duration * 1000).toLong()
                // Every 10 seconds
                if (++ticks % 20 == 0) saveProgress()
            }
            delay(500)
        }
    }

    // While links are found and the video opens or buffers, a spinner turns over the video
    val indicator = when {
        state.exhausted -> null
        state.selected == null -> if (state.links.isEmpty()) "Finding links…" else "Finding links · ${state.links.size} found"
        status.buffering -> status.bufferPercent?.takeIf { it in 1..99 }?.let { "Buffering $it%" } ?: "Loading…"
        else -> null
    }
    val currentIndicator by rememberUpdatedState(indicator)
    LaunchedEffect(player, indicator != null) {
        if (indicator == null) {
            player.overlay(null)
            return@LaunchedEffect
        }
        var step = 0
        while (true) {
            currentIndicator?.let { player.overlay(spinnerAss(step++, it)) }
            delay(80)
        }
    }

    fun showSubtitle(subtitle: SubtitleFile) {
        subtitleMessage = null
        subtitleChoice = SubtitleChoice.Extension(subtitle.url)
        val file = downloaded[subtitle.url]
        val track = file?.let { status.tracks.firstOrNull { track -> track.isFile(it) } }
        if (track != null) {
            player.selectSubtitle(track.id)
            return
        }
        loadingSubtitle = subtitle.url
        scope.launch {
            runCatching { Subtitles.download(subtitle) }
                .onSuccess {
                    downloaded[subtitle.url] = it
                    player.addSubtitle(it, subtitle.lang)
                }
                .onFailure { subtitleMessage = "Subtitle could not be loaded: ${it.message}" }
            loadingSubtitle = null
        }
    }

    // Keys reach the player through the window, before anything focused: the buttons that could hold
    // the focus hide in full screen, which used to leave the keys going nowhere
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        when (event.key) {
            Key.Spacebar, Key.K -> player.togglePause()
            Key.DirectionLeft, Key.J -> player.seek(-10.0)
            Key.DirectionRight, Key.L -> player.seek(10.0)
            Key.DirectionUp -> player.changeVolume(5)
            Key.DirectionDown -> player.changeVolume(-5)
            // The app hides at once, and the video must not carry on behind it: the window handles M next
            Key.M -> {
                player.pause()
                return false
            }
            Key.F -> currentFullscreen()
            Key.Escape -> if (panel != null) panel = null else return false
            else -> return false
        }
        // What the key changed shows in the bars, also in full screen
        if (event.key != Key.Escape) activity = System.nanoTime()
        return true
    }
    DisposableEffect(Unit) {
        onKeys(::onKey)
        onDispose { onKeys(null) }
    }
    // Clicks on the bars outside their buttons close an open panel
    val closePanel = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = null }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Moves over the bars, which the video does not see
            .onPointerEvent(PointerEventType.Move) { pointerMoved() },
    ) {
        if (showBars) TopBar(
            title = listOfNotNull(viewModel.request.title, viewModel.request.episodeLabel).joinToString(" · "),
            line = subtitleMessage ?: when {
                state.exhausted && state.links.isEmpty() -> "No playable links found" +
                        if (state.skipped > 0) " (${state.skipped} torrent or DRM links cannot play on desktop)" else ""
                state.exhausted -> "None of the ${state.links.size} links could be played"
                state.selected == null -> "Finding links… ${state.links.size} found"
                else -> state.selectedLink?.let(LinksViewModel::label)
            },
            error = subtitleMessage != null || state.exhausted,
            loading = state.loading,
            onBack = onBack,
            onRetry = if (state.exhausted) ({ viewModel.onAction(LinksAction.Retry) }) else null,
            modifier = closePanel,
        )
        // The video is a native window, so nothing can be drawn over it: panels open beside it
        Row(Modifier.fillMaxWidth().weight(1f)) {
            SwingPanel(factory = { player.canvas }, modifier = Modifier.weight(1f).fillMaxHeight(), background = Color.Black)
            val open = panel
            if (open != null && showBars) SidePanel(
                panel = open,
                state = state,
                status = status,
                downloaded = downloaded,
                loadingSubtitle = loadingSubtitle,
                onSelect = { viewModel.onAction(LinksAction.Select(it)) },
                onSubtitle = ::showSubtitle,
                onSubtitleTrack = { id ->
                    subtitleChoice = if (id == null) SubtitleChoice.Off else SubtitleChoice.Default
                    player.selectSubtitle(id)
                },
                onSubDelay = { if (it == 0.0) player.resetSubDelay() else player.changeSubDelay(it) },
                onAudioTrack = player::selectAudio,
                onSpeed = player::setSpeed,
            )
        }
        if (showBars) ControlBar(
            player = player,
            status = status,
            state = state,
            panel = panel,
            subtitleOn = status.tracks.any { it.type == "sub" && it.selected },
            fullscreen = fullscreen,
            onPanel = { panel = it },
            onToggleFullscreen = onToggleFullscreen,
            modifier = closePanel,
        )
    }
}

/** "English", or "English 1", "English 2" when there are several, by url */
private fun subtitleLabels(subtitles: List<SubtitleFile>): Map<String, String> {
    val counts = subtitles.groupingBy { it.lang }.eachCount()
    val seen = HashMap<String, Int>()
    return subtitles.associate { sub ->
        val n = seen.merge(sub.lang, 1, Int::plus)!!
        sub.url to if (counts.getValue(sub.lang) > 1) "${sub.lang} $n" else sub.lang
    }
}

private fun host(url: String): String? = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()

private fun MpvTrack.isFile(file: File) = external && externalFilename?.let { File(it).absolutePath.equals(file.absolutePath, ignoreCase = true) } == true

private enum class Panel(val title: String) { Source("Source"), Subtitles("Subtitles"), Audio("Audio"), Speed("Speed") }

private val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

private fun speedLabel(speed: Double) = "%s×".format(if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString())

/** The player is always dark, whatever the app's theme, like most video players */
private object PlayerColors {
    val bar = Color(0xFF111114)
    val panel = Color(0xFF18181C)
    val content = Color.White
    val secondary = Color.White.copy(alpha = 0.68f)
    val track = Color.White.copy(alpha = 0.22f)
    val buffered = Color.White.copy(alpha = 0.42f)
    val selected = Color.White.copy(alpha = 0.10f)
}

/** The app's accent, in the tone that reads on a dark background */
@Composable
private fun accent(): Color {
    val scheme = MaterialTheme.colorScheme
    // A light theme's primary is dark, its inverse is the light tone of the same colour
    return if (scheme.background.luminance() > 0.5f) scheme.inversePrimary else scheme.primary
}

/**
 * A spinner and a line of text, drawn by mpv over the video: twelve dots around a dim disc, the
 * brightest one moving round as [step] grows.
 */
private fun spinnerAss(step: Int, text: String): String {
    val cx = 640.0
    val cy = 330.0
    val lines = mutableListOf<String>()
    // Dim disc behind, so the spinner reads on a bright frame
    lines += "{\\an7\\pos(${cx - 56},${cy - 56})\\bord0\\shad0\\1c&H000000&\\1a&H70&\\p1}" + circle(56.0) + "{\\p0}"
    for (i in 0 until 12) {
        val angle = Math.toRadians(i * 30.0 - 90)
        val x = cx + 30 * kotlin.math.cos(angle)
        val y = cy + 30 * kotlin.math.sin(angle)
        // The head is opaque, the dots behind it fade out
        val age = ((step - i) % 12 + 12) % 12
        val alpha = (age * 20).coerceAtMost(0xD0)
        lines += "{\\an7\\pos(${x - 4.5},${y - 4.5})\\bord0\\shad0\\1c&HFFFFFF&\\1a&H%02X&\\p1}".format(alpha) + circle(4.5) + "{\\p0}"
    }
    lines += "{\\an8\\pos($cx,${cy + 70})\\fs24\\bord1.5\\shad0\\3c&H000000&\\1c&HFFFFFF&}" + text.replace("{", "(").replace("}", ")")
    return lines.joinToString("\n")
}

/** An ASS drawing of a circle of radius [r], from 0,0 to 2r,2r */
private fun circle(r: Double): String {
    val k = r * 0.5523
    fun n(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)
    return "m ${n(r)} 0 b ${n(r + k)} 0 ${n(2 * r)} ${n(r - k)} ${n(2 * r)} ${n(r)} " +
        "b ${n(2 * r)} ${n(r + k)} ${n(r + k)} ${n(2 * r)} ${n(r)} ${n(2 * r)} " +
        "b ${n(r - k)} ${n(2 * r)} 0 ${n(r + k)} 0 ${n(r)} " +
        "b 0 ${n(r - k)} ${n(r - k)} 0 ${n(r)} 0"
}

@Composable
private fun TopBar(
    title: String,
    line: String?,
    error: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(PlayerColors.bar).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(Res.drawable.arrow_back), contentDescription = "Back", tint = PlayerColors.content)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PlayerColors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else PlayerColors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = PlayerColors.secondary)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again", color = accent()) }
    }
}

/**
 * The bar under the video: a seek bar across the top showing what is played and what is loaded,
 * then play, skip, volume and the time on the left, and the pickers and full screen on the right.
 */
@Composable
private fun ControlBar(
    player: MpvPlayer,
    status: MpvStatus,
    state: LinksState,
    panel: Panel?,
    subtitleOn: Boolean,
    fullscreen: Boolean,
    onPanel: (Panel?) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // While dragging, and after letting go until mpv got there, the bar shows the target, not the old position
    var dragging by remember { mutableStateOf<Float?>(null) }
    var hovering by remember { mutableStateOf<Float?>(null) }
    var seekingTo by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    LaunchedEffect(status) {
        seekingTo?.let { (target, at) ->
            if (abs(status.position - target) < 2 || System.currentTimeMillis() - at > 3000) seekingTo = null
        }
    }
    val duration = status.duration.toFloat()
    val position = dragging ?: seekingTo?.first ?: status.position.toFloat()
    val accent = accent()

    Column(modifier.fillMaxWidth().background(PlayerColors.bar).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)) {
        SeekBar(
            value = if (duration > 0) position / duration else 0f,
            buffered = if (duration > 0) (status.bufferedTo.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            enabled = duration > 0,
            accent = accent,
            onChange = { dragging = it * duration },
            onHover = { hovering = it?.times(duration) },
            onDone = {
                dragging?.let {
                    player.seekTo(it.toDouble())
                    seekingTo = it to System.currentTimeMillis()
                }
                dragging = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon(
                if (status.paused) Res.drawable.play_arrow_24px else Res.drawable.pause_24px,
                if (status.paused) "Play" else "Pause",
                size = 30.dp,
                onClick = player::togglePause,
            )
            SkipButton(forward = false) { player.seek(-10.0) }
            SkipButton(forward = true) { player.seek(10.0) }
            var volume by remember { mutableStateOf<Float?>(null) }
            PlayerIcon(
                if (status.muted || status.volume == 0) Res.drawable.ic_baseline_volume_mute_24 else Res.drawable.ic_baseline_volume_up_24,
                if (status.muted) "Unmute" else "Mute",
                onClick = player::toggleMute,
            )
            SeekBar(
                value = (volume ?: status.volume.toFloat()) / 100f,
                accent = PlayerColors.content,
                onChange = {
                    volume = it * 100
                    player.setVolume((it * 100).toInt())
                },
                onDone = { volume = null },
                modifier = Modifier.width(96.dp),
            )
            // The hovered time shows in place of the position, to see where a click would go
            val shown = hovering ?: position
            Text(
                if (duration > 0) "${formatTime((shown * 1000).toLong())} / ${formatTime((duration * 1000).toLong())}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hovering != null) accent else PlayerColors.content,
                modifier = Modifier.padding(start = 16.dp),
            )
            Box(Modifier.weight(1f))

            val subtitleTracks = state.subtitles.isNotEmpty() || status.tracks.any { it.type == "sub" }
            PanelButton(Res.drawable.ic_baseline_subtitles_24, "Subtitles", panel == Panel.Subtitles, active = subtitleOn, enabled = subtitleTracks) {
                onPanel(if (panel == Panel.Subtitles) null else Panel.Subtitles)
            }
            val audio = status.tracks.count { it.type == "audio" }
            PanelButton(
                Res.drawable.ic_baseline_audiotrack_24, "Audio", panel == Panel.Audio,
                label = if (audio > 1) "$audio" else null,
            ) { onPanel(if (panel == Panel.Audio) null else Panel.Audio) }
            PanelButton(
                Res.drawable.ic_baseline_speed_24, "Speed", panel == Panel.Speed,
                label = if (status.speed != 1.0) speedLabel(status.speed) else null,
                active = status.speed != 1.0,
            ) { onPanel(if (panel == Panel.Speed) null else Panel.Speed) }
            PanelButton(
                Res.drawable.ic_baseline_hd_24, "Source", panel == Panel.Source,
                label = state.selectedLink?.let { Qualities.getStringByInt(it.quality).ifBlank { null } } ?: "Source",
                enabled = state.links.isNotEmpty(),
            ) { onPanel(if (panel == Panel.Source) null else Panel.Source) }
            PlayerIcon(
                if (fullscreen) Res.drawable.baseline_fullscreen_exit_24 else Res.drawable.baseline_fullscreen_24,
                if (fullscreen) "Leave full screen" else "Full screen",
                onClick = onToggleFullscreen,
            )
        }
    }
}

@Composable
private fun PlayerIcon(
    icon: DrawableResource,
    description: String,
    size: Dp = 24.dp,
    tint: Color = PlayerColors.content,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size))
    }
}

/** Back or forward 10 seconds: the replay arrow with a 10 inside, mirrored to go forward */
@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(Res.drawable.ic_baseline_replay_24),
                contentDescription = if (forward) "Forward 10 seconds" else "Back 10 seconds",
                tint = PlayerColors.content,
                modifier = Modifier.size(28.dp).graphicsLayer { if (forward) scaleX = -1f },
            )
            Text("10", color = PlayerColors.content, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Opens a picker: tinted while it is open or its setting is on, with a short label when there is one */
@Composable
private fun PanelButton(
    icon: DrawableResource,
    description: String,
    open: Boolean,
    label: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> PlayerColors.content.copy(alpha = 0.3f)
        open || active -> accent()
        else -> PlayerColors.content
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (open) PlayerColors.selected else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = color, modifier = Modifier.size(22.dp))
        label?.let { Text(it, color = color, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * A thin bar that thickens under the mouse: [value] and [buffered] are fractions. Clicking or
 * dragging calls [onChange] with the fraction under the mouse, [onDone] when the mouse is let go.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeekBar(
    value: Float,
    accent: Color,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    enabled: Boolean = true,
    onHover: (Float?) -> Unit = {},
) {
    var width by remember { mutableStateOf(1) }
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val currentChange by rememberUpdatedState(onChange)
    val currentDone by rememberUpdatedState(onDone)
    val currentHover by rememberUpdatedState(onHover)
    fun fraction(x: Float) = (x / width).coerceIn(0f, 1f)
    val thick = enabled && (hovered || pressed)
    Box(
        modifier
            .height(20.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = false
                currentHover(null)
            }
            .onPointerEvent(PointerEventType.Move) { if (enabled) currentHover(fraction(it.changes.first().position.x)) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    currentChange(fraction(down.position.x))
                    drag(down.id) { change ->
                        currentChange(fraction(change.position.x))
                        change.consume()
                    }
                    pressed = false
                    currentDone()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (thick) 6.dp else 3.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(PlayerColors.track, cornerRadius = r)
            if (buffered > 0) drawRoundRect(PlayerColors.buffered, size = Size(size.width * buffered, size.height), cornerRadius = r)
            drawRoundRect(accent, size = Size(size.width * value.coerceIn(0f, 1f), size.height), cornerRadius = r)
        }
        if (thick) Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawCircle(accent, radius = size.height / 2, center = Offset(size.width * value.coerceIn(0f, 1f), size.height / 2))
        }
    }
}

@Composable
private fun SidePanel(
    panel: Panel,
    state: LinksState,
    status: MpvStatus,
    downloaded: Map<String, File>,
    loadingSubtitle: String?,
    onSelect: (String) -> Unit,
    onSubtitle: (SubtitleFile) -> Unit,
    onSubtitleTrack: (Int?) -> Unit,
    onSubDelay: (Double) -> Unit,
    onAudioTrack: (Int) -> Unit,
    onSpeed: (Double) -> Unit,
) {
    LazyColumn(Modifier.width(380.dp).fillMaxHeight().background(PlayerColors.panel)) {
        item { Text(panel.title, style = MaterialTheme.typography.titleMedium, color = PlayerColors.content, modifier = Modifier.padding(16.dp)) }
        when (panel) {
            // Names are shown whole: many sources differ only at the end of their name
            Panel.Source -> items(state.links) { link ->
                Choice(
                    text = LinksViewModel.label(link),
                    detail = state.failed[link.url]?.let { "Failed: $it" } ?: link.source.takeIf { it != link.name },
                    selected = link.url == state.selected,
                    error = link.url in state.failed,
                    maxLines = Int.MAX_VALUE,
                ) { onSelect(link.url) }
            }

            Panel.Subtitles -> {
                val tracks = status.tracks.filter { it.type == "sub" }
                val shown = tracks.firstOrNull { it.selected }
                // Timing first: a video can hold dozens of subtitle tracks
                if (shown != null) item { SubtitleTiming(status.subDelay, onSubDelay) }
                item { Choice("Off", selected = shown == null) { onSubtitleTrack(null) } }
                // Sorted by language. Extensions often give several of one: they are numbered and show where they come from
                val subtitles = state.subtitles.sortedBy { it.lang.lowercase() }
                val labels = subtitleLabels(subtitles)
                items(subtitles, key = { it.url }) { sub ->
                    val track = downloaded[sub.url]?.let { file -> tracks.firstOrNull { it.isFile(file) } }
                    Choice(
                        labels.getValue(sub.url),
                        detail = if (loadingSubtitle == sub.url) "Loading…" else listOfNotNull("From the extension", host(sub.url)).joinToString(" · "),
                        selected = track?.selected == true,
                    ) { onSubtitle(sub) }
                }
                items(tracks.filter { !it.external }) { track ->
                    Choice(track.label, detail = listOf("In the video", track.details).filter { it.isNotEmpty() }.joinToString(" · "), selected = track.selected) {
                        onSubtitleTrack(track.id)
                    }
                }
            }

            Panel.Audio -> {
                val tracks = status.tracks.filter { it.type == "audio" }
                items(tracks) { track ->
                    Choice(
                        track.label,
                        detail = listOf(if (track.external) "From the extension" else "In the video", track.details).filter { it.isNotEmpty() }.joinToString(" · "),
                        selected = track.selected,
                    ) { onAudioTrack(track.id) }
                }
                // Many sources carry one language each: say so, rather than leave an empty choice
                if (tracks.size <= 1) item {
                    Text(
                        if (tracks.isEmpty()) "No audio track yet. It shows once the video starts."
                        else "This source has one audio track. Other sources may be in other languages: look in Source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PlayerColors.secondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Panel.Speed -> items(speeds) { speed ->
                Choice(speedLabel(speed) + if (speed == 1.0) " (normal)" else "", selected = abs(status.speed - speed) < 0.01) { onSpeed(speed) }
            }
        }
    }
}

/** Moves the subtitles earlier or later when they are out of sync with the speech */
@Composable
private fun SubtitleTiming(delay: Double, onChange: (Double) -> Unit) {
    val accent = accent()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Timing", style = MaterialTheme.typography.titleSmall, color = PlayerColors.content)
        Text(
            when {
                abs(delay) < 0.05 -> "In sync with the video"
                delay > 0 -> "Shown %.1f s later".format(delay)
                else -> "Shown %.1f s earlier".format(-delay)
            },
            style = MaterialTheme.typography.bodySmall,
            color = PlayerColors.secondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(-0.5) }) { Text("Earlier", color = accent) }
            TextButton(onClick = { onChange(0.5) }) { Text("Later", color = accent) }
            if (abs(delay) >= 0.05) TextButton(onClick = { onChange(0.0) }) { Text("Reset", color = accent) }
        }
    }
}

@Composable
private fun Choice(
    text: String,
    detail: String? = null,
    selected: Boolean,
    error: Boolean = false,
    maxLines: Int = 2,
    onClick: () -> Unit,
) {
    val accent = accent()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) PlayerColors.selected else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) accent else PlayerColors.content,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else PlayerColors.secondary,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) Text("✓", color = accent, modifier = Modifier.padding(start = 8.dp))
    }
}
