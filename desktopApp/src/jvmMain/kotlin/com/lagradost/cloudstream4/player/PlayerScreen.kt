package com.lagradost.cloudstream4.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream4.browse.Message
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.arrow_back
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Plays a movie or episode. The links load in the background and the best one starts on its own.
 * The bar on top switches source, subtitles and audio; mpv's own controls handle seeking.
 * Space pauses, the arrows seek and change volume, F toggles full screen and Escape goes back.
 */
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
    var subtitleError by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    val currentBack by rememberUpdatedState(onBack)
    val currentFullscreen by rememberUpdatedState(onToggleFullscreen)

    // A new link was picked, by hand or because the last one failed
    val selected = state.selectedLink
    LaunchedEffect(selected?.url) {
        if (selected != null) player.play(selected)
    }
    LaunchedEffect(player) {
        player.events.collect { event ->
            when (event) {
                MpvEvent.Back -> currentBack()
                MpvEvent.ToggleFullscreen -> currentFullscreen()
                is MpvEvent.Failed -> viewModel.onAction(LinksAction.Failed(event.url, event.message))
                MpvEvent.Ended -> Unit
            }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            status = player.status()
            delay(1000)
        }
    }

    fun showSubtitle(subtitle: SubtitleFile) {
        subtitleError = null
        scope.launch {
            runCatching { Subtitles.download(subtitle) }
                .onSuccess { player.addSubtitle(it, subtitle.lang) }
                .onFailure { subtitleError = "Subtitle could not be loaded: ${it.message}" }
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                    Key.F -> onToggleFullscreen()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        if (!fullscreen) TopBar(
            state = state,
            status = status,
            title = listOfNotNull(viewModel.request.title, viewModel.request.episodeLabel).joinToString(" · "),
            message = subtitleError,
            panel = panel,
            onPanel = { panel = it },
            onBack = onBack,
            onRetry = { viewModel.onAction(LinksAction.Retry) },
            onToggleFullscreen = onToggleFullscreen,
        )
        // The video is a native window, so nothing can be drawn over it: messages go in the bar above
        Row(Modifier.fillMaxWidth().weight(1f)) {
            SwingPanel(factory = { player.canvas }, modifier = Modifier.weight(1f).fillMaxHeight(), background = Color.Black)
            val open = panel
            if (open != null && !fullscreen) SidePanel(
                panel = open,
                state = state,
                status = status,
                onSelect = { viewModel.onAction(LinksAction.Select(it)) },
                onSubtitle = ::showSubtitle,
                onSubtitleTrack = player::selectSubtitle,
                onAudioTrack = player::selectAudio,
            )
        }
    }
}

/** The pickers open beside the video: the video is a native window, so nothing can be drawn over it */
private enum class Panel(val title: String) { Source("Source"), Subtitles("Subtitles"), Audio("Audio") }

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
    onToggleFullscreen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
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
            }
            val label = if (entry == Panel.Source && state.links.isNotEmpty()) "Source · ${state.links.size}" else entry.title
            // The audio button only shows when there is a choice to make
            if (entry != Panel.Audio || enabled) {
                TextButton(onClick = { onPanel(if (panel == entry) null else entry) }, enabled = enabled) {
                    Text((if (panel == entry) "▸ " else "") + label)
                }
            }
        }
        TextButton(onClick = onToggleFullscreen) { Text("Full screen") }
    }
}

@Composable
private fun SidePanel(
    panel: Panel,
    state: LinksState,
    status: MpvStatus,
    onSelect: (String) -> Unit,
    onSubtitle: (SubtitleFile) -> Unit,
    onSubtitleTrack: (Int?) -> Unit,
    onAudioTrack: (Int) -> Unit,
) {
    LazyColumn(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        item { Text(panel.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
        when (panel) {
            Panel.Source -> items(state.links) { link ->
                Choice(
                    text = LinksViewModel.label(link),
                    detail = state.failed[link.url]?.let { "Failed: $it" } ?: link.source.takeIf { it != link.name },
                    selected = link.url == state.selected,
                    error = link.url in state.failed,
                ) { onSelect(link.url) }
            }

            Panel.Subtitles -> {
                val tracks = status.tracks.filter { it.type == "sub" }
                item { Choice("Off", selected = tracks.none { it.selected }) { onSubtitleTrack(null) } }
                items(tracks) { track ->
                    Choice(track.label, detail = if (track.external) "From the extension" else "In the video", selected = track.selected) {
                        onSubtitleTrack(track.id)
                    }
                }
                // Extension subtitles not added yet, each is downloaded when picked
                val notAdded = state.subtitles.filter { sub -> tracks.none { it.external && it.title == sub.lang } }
                items(notAdded) { sub -> Choice(sub.lang, detail = "From the extension", selected = false) { onSubtitle(sub) } }
            }

            Panel.Audio -> items(status.tracks.filter { it.type == "audio" }) { track ->
                Choice(track.label, selected = track.selected) { onAudioTrack(track.id) }
            }
        }
    }
}

@Composable
private fun Choice(text: String, detail: String? = null, selected: Boolean, error: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
