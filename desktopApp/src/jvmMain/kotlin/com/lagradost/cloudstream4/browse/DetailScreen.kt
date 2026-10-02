package com.lagradost.cloudstream4.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream4.detail.DetailAction
import com.lagradost.cloudstream4.detail.DetailState
import com.lagradost.cloudstream4.detail.DetailStatus
import com.lagradost.cloudstream4.detail.DetailViewModel
import com.lagradost.cloudstream4.detail.PlayRequest
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.arrow_back
import com.lagradost.cloudstream4.generated.resources.play_arrow_24px
import org.jetbrains.compose.resources.painterResource

/** The details of a movie or series, with its episodes and similar titles */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpen: (apiName: String, url: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(painterResource(Res.drawable.arrow_back), contentDescription = "Back")
        }
        when (val status = state.status) {
            DetailStatus.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is DetailStatus.Failed -> Message("Could not load this title", status.message) {
                Button(onClick = { viewModel.onAction(DetailAction.Retry) }) { Text("Try again") }
            }

            DetailStatus.Done -> Details(state, viewModel, onPlay, onOpen)
        }
    }
}

@Composable
private fun Details(
    state: DetailState,
    viewModel: DetailViewModel,
    onPlay: (PlayRequest) -> Unit,
    onOpen: (apiName: String, url: String) -> Unit,
) {
    val response = state.response ?: return
    val season = state.seasons.getOrNull(state.selectedSeason)
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item { Header(response, state, onPlay = { viewModel.playRequest()?.let(onPlay) }) }

        if (state.dubs.size > 1 || state.seasons.size > 1) item {
            Choices(state, viewModel::onAction)
        }

        if (season != null) {
            item {
                Text(
                    "${season.label} · ${season.episodes.size} episodes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(season.episodes) { episode ->
                EpisodeRow(episode) { viewModel.playRequest(episode)?.let(onPlay) }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            }
        }

        val recommendations = response.recommendations.orEmpty()
        if (recommendations.isNotEmpty()) item {
            PosterRow(
                title = "More like this",
                items = recommendations,
                horizontal = false,
                modifier = Modifier.padding(top = 24.dp),
                onItemClick = { onOpen(it.apiName, it.url) },
            )
        }
    }
}

@Composable
private fun Header(response: LoadResponse, state: DetailState, onPlay: () -> Unit) {
    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        RemoteImage(
            response.posterUrl,
            response.posterHeaders,
            contentDescription = response.name,
            modifier = Modifier.width(200.dp).height(300.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.name, style = MaterialTheme.typography.headlineMedium)
            val facts = listOfNotNull(
                response.year?.toString(),
                response.type.name,
                response.duration?.let { "$it min" },
                response.contentRating,
                response.apiName,
            )
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            response.tags?.takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            response.plot?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            when {
                state.unsupported != null -> Text(state.unsupported.orEmpty(), color = MaterialTheme.colorScheme.error)
                state.movieData != null -> Button(onClick = onPlay, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(painterResource(Res.drawable.play_arrow_24px), contentDescription = null)
                    Text("Play", modifier = Modifier.padding(start = 8.dp))
                }
                state.seasons.isEmpty() -> Text("No episodes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(state: DetailState, onAction: (DetailAction) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.dubs.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.dubs.forEach { dub ->
                FilterChip(
                    selected = dub == state.selectedDub,
                    onClick = { onAction(DetailAction.SelectDub(dub)) },
                    label = { Text(if (dub == DubStatus.None) "Default" else dub.name) },
                )
            }
        }
        if (state.seasons.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.seasons.forEachIndexed { index, season ->
                FilterChip(
                    selected = index == state.selectedSeason,
                    onClick = { onAction(DetailAction.SelectSeason(index)) },
                    label = { Text(season.label) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (episode.posterUrl != null) {
            RemoteImage(episode.posterUrl, null, contentDescription = null, modifier = Modifier.width(160.dp).height(90.dp))
        } else {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(Res.drawable.play_arrow_24px), contentDescription = null) }
        }
        Column(Modifier.weight(1f)) {
            Text(DetailViewModel.episodeLabel(episode), style = MaterialTheme.typography.titleSmall)
            episode.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        episode.runTime?.let { Text("$it min", style = MaterialTheme.typography.bodySmall) }
    }
}
