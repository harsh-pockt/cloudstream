package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream4.browse.Message
import com.lagradost.cloudstream4.browse.formatTime
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.arrow_back
import com.lagradost.cloudstream4.generated.resources.baseline_fullscreen_24
import com.lagradost.cloudstream4.generated.resources.baseline_fullscreen_exit_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_volume_mute_24
import com.lagradost.cloudstream4.generated.resources.ic_baseline_volume_up_24
import com.lagradost.cloudstream4.generated.resources.pause_24px
import com.lagradost.cloudstream4.generated.resources.play_arrow_24px
import com.lagradost.cloudstream4.library.DataStoreWatchStore
import com.lagradost.cloudstream4.library.saveProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * The bar on top switches source, subtitles, audio and speed; the bar below plays, seeks and sets
 * the volume. In full screen the bars show while the mouse moves and hide after a few seconds.
 * Space pauses, the arrows seek and change volume, M mutes, F toggles full screen and Escape closes
 * an open panel or goes back. A click on the video closes an open panel, or pauses.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    viewModel: LinksViewModel,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
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

    // A new link was picked, by hand or because the last one failed. The subtitle picked stays on
    val selected = state.selectedLink
    LaunchedEffect(selected?.url) {
        if (selected == null) return@LaunchedEffect
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
                MpvEvent.Click -> if (panel != null) panel = null else player.togglePause()
                is MpvEvent.Failed -> viewModel.onAction(LinksAction.Failed(event.url, event.message))
                MpvEvent.Ended -> saveProgress(playedTo[1], playedTo[1])
            }
        }
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            status = player.status()
            if (status.duration > 0 && status.position > 0) {
                playedTo[0] = (status.position * 1000).toLong()
                playedTo[1] = (status.duration * 1000).toLong()
                // Every 10 seconds
                if (++ticks % 20 == 0) saveProgress()
            }
            delay(500)
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

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Clicks on the bars outside their buttons close an open panel
    val closePanel = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = null }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Moves over the bars, which the video does not see
            .onPointerEvent(PointerEventType.Move) { pointerMoved() }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Spacebar -> player.togglePause()
                    Key.DirectionLeft -> player.seek(-10.0)
                    Key.DirectionRight -> player.seek(10.0)
                    Key.DirectionUp -> player.changeVolume(5)
                    Key.DirectionDown -> player.changeVolume(-5)
                    Key.M -> player.toggleMute()
                    Key.F -> onToggleFullscreen()
                    Key.Escape -> if (panel != null) panel = null else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        if (showBars) TopBar(
            state = state,
            status = status,
            title = listOfNotNull(viewModel.request.title, viewModel.request.episodeLabel).joinToString(" · "),
            message = subtitleMessage,
            panel = panel,
            onPanel = { panel = it },
            onBack = onBack,
            onRetry = { viewModel.onAction(LinksAction.Retry) },
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
        if (showBars) ControlBar(player, status, fullscreen, onToggleFullscreen, closePanel)
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

@Composable
private fun TopBar(
    state: LinksState,
    status: MpvStatus,
    title: String,
    message: String?,
    panel: Panel?,
    onPanel: (Panel?) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back), contentDescription = "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = message ?: when {
                state.exhausted && state.links.isEmpty() -> "No playable links found" +
                        if (state.skipped > 0) " (${state.skipped} torrent or DRM links cannot play on desktop)" else ""
                state.exhausted -> "None of the ${state.links.size} links could be played"
                state.selected == null -> "Finding links… ${state.links.size} found"
                status.buffering -> "Buffering…"
                else -> state.selectedLink?.let(LinksViewModel::label)
            }
            line?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (state.exhausted) TextButton(onClick = onRetry) { Text("Try again") }

        val audioTracks = status.tracks.count { it.type == "audio" }
        Panel.entries.forEach { entry ->
            val enabled = when (entry) {
                Panel.Source -> state.links.isNotEmpty()
                Panel.Subtitles -> state.subtitles.isNotEmpty() || status.tracks.any { it.type == "sub" }
                Panel.Audio -> audioTracks > 1
                Panel.Speed -> true
            }
            val label = when {
                entry == Panel.Source && state.links.isNotEmpty() -> "Source · ${state.links.size}"
                entry == Panel.Speed && status.speed != 1.0 -> "Speed · ${speedLabel(status.speed)}"
                else -> entry.title
            }
            // The audio button only shows when there is a choice to make
            if (entry != Panel.Audio || enabled) {
                TextButton(onClick = { onPanel(if (panel == entry) null else entry) }, enabled = enabled) {
                    Text((if (panel == entry) "▸ " else "") + label)
                }
            }
        }
    }
}

/** Play and pause, the seek bar with the time, volume and full screen */
@Composable
private fun ControlBar(player: MpvPlayer, status: MpvStatus, fullscreen: Boolean, onToggleFullscreen: () -> Unit, modifier: Modifier = Modifier) {
    // While dragging, and after letting go until mpv got there, the bar shows the target, not the old position
    var dragging by remember { mutableStateOf<Float?>(null) }
    var seekingTo by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    LaunchedEffect(status) {
        seekingTo?.let { (target, at) ->
            if (abs(status.position - target) < 2 || System.currentTimeMillis() - at > 3000) seekingTo = null
        }
    }
    var volume by remember { mutableStateOf<Float?>(null) }
    val duration = status.duration.toFloat()
    val position = dragging ?: seekingTo?.first ?: status.position.toFloat()

    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = player::togglePause) {
            Icon(
                painterResource(if (status.paused) Res.drawable.play_arrow_24px else Res.drawable.pause_24px),
                contentDescription = if (status.paused) "Play" else "Pause",
            )
        }
        TextButton(onClick = { player.seek(-10.0) }) { Text("−10 s") }
        TextButton(onClick = { player.seek(10.0) }) { Text("+10 s") }
        Text(
            if (duration > 0) "${formatTime((position * 1000).toLong())} / ${formatTime((duration * 1000).toLong())}" else "--:--",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Slider(
            value = position.coerceIn(0f, duration.coerceAtLeast(1f)),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let {
                    player.seekTo(it.toDouble())
                    seekingTo = it to System.currentTimeMillis()
                }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1f),
            enabled = duration > 0,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = player::toggleMute) {
            Icon(
                painterResource(if (status.muted || status.volume == 0) Res.drawable.ic_baseline_volume_mute_24 else Res.drawable.ic_baseline_volume_up_24),
                contentDescription = if (status.muted) "Unmute" else "Mute",
            )
        }
        Slider(
            value = volume ?: status.volume.toFloat(),
            onValueChange = {
                volume = it
                player.setVolume(it.toInt())
            },
            onValueChangeFinished = { volume = null },
            valueRange = 0f..100f,
            modifier = Modifier.width(110.dp),
        )
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                painterResource(if (fullscreen) Res.drawable.baseline_fullscreen_exit_24 else Res.drawable.baseline_fullscreen_24),
                contentDescription = if (fullscreen) "Leave full screen" else "Full screen",
            )
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
    LazyColumn(Modifier.width(380.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        item { Text(panel.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
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
                // Every extension subtitle, downloaded the first time it is picked
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
                    Choice(track.label, detail = "In the video", selected = track.selected) { onSubtitleTrack(track.id) }
                }
            }

            Panel.Audio -> items(status.tracks.filter { it.type == "audio" }) { track ->
                Choice(track.label, selected = track.selected) { onAudioTrack(track.id) }
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
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Timing", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                abs(delay) < 0.05 -> "In sync with the video"
                delay > 0 -> "Shown %.1f s later".format(delay)
                else -> "Shown %.1f s earlier".format(-delay)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(-0.5) }) { Text("Earlier") }
            TextButton(onClick = { onChange(0.5) }) { Text("Later") }
            if (abs(delay) >= 0.05) TextButton(onClick = { onChange(0.0) }) { Text("Reset") }
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
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
