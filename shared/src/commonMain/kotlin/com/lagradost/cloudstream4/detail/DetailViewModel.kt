package com.lagradost.cloudstream4.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.providers.loadSafely
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the player needs to find and play the links of one movie or episode */
data class PlayRequest(
    val apiName: String,
    val title: String,
    /** For example "S1 E3 · Pilot", null for a movie */
    val episodeLabel: String?,
    val data: String,
)

/** One season of episodes, or all episodes when the provider has no seasons */
data class SeasonGroup(
    val season: Int?,
    val label: String,
    val episodes: List<Episode>,
)

sealed interface DetailStatus {
    data object Loading : DetailStatus
    data class Failed(val message: String) : DetailStatus
    data object Done : DetailStatus
}

data class DetailState(
    val status: DetailStatus = DetailStatus.Loading,
    val response: LoadResponse? = null,
    /** Set for movies and live streams, played directly */
    val movieData: String? = null,
    /** Dubbed, subbed... when the provider splits episodes that way, otherwise empty */
    val dubs: List<DubStatus> = emptyList(),
    val selectedDub: DubStatus? = null,
    val seasons: List<SeasonGroup> = emptyList(),
    val selectedSeason: Int = 0,
    /** Why this item cannot be played on desktop, for example a torrent */
    val unsupported: String? = null,
)

sealed interface DetailAction {
    data class SelectSeason(val index: Int) : DetailAction
    data class SelectDub(val dub: DubStatus) : DetailAction
    data object Retry : DetailAction
}

/** The details page of one item: its info, and either a play button or its seasons and episodes */
class DetailViewModel(
    private val api: MainAPI,
    private val url: String,
) : ViewModel(), StateContainer<DetailState> by DefaultStateContainer(DetailState()),
    ActionHandler<DetailAction> {

    private var loadJob: Job? = null
    /** Episodes per dub, or under null when the provider does not split them */
    private var episodesByDub: Map<DubStatus?, List<Episode>> = emptyMap()

    init {
        load()
    }

    override fun onAction(action: DetailAction) {
        when (action) {
            is DetailAction.SelectSeason -> updateState {
                copy(selectedSeason = action.index.coerceIn(0, (seasons.size - 1).coerceAtLeast(0)))
            }

            is DetailAction.SelectDub -> {
                val response = state.value.response ?: return
                if (action.dub !in episodesByDub) return
                updateState {
                    copy(selectedDub = action.dub, seasons = groupSeasons(response, episodesByDub[action.dub].orEmpty()), selectedSeason = 0)
                }
            }

            DetailAction.Retry -> load()
        }
    }

    private fun load() {
        loadJob?.cancel()
        updateState { DetailState() }
        loadJob = viewModelScope.launch {
            when (val result = api.loadSafely(url)) {
                is Resource.Success -> show(result.value)
                is Resource.Failure -> updateState { copy(status = DetailStatus.Failed(result.errorString)) }
                is Resource.Loading -> Unit
            }
        }
    }

    private fun show(response: LoadResponse) {
        episodesByDub = when (response) {
            is TvSeriesLoadResponse -> mapOf(null to response.episodes)
            is AnimeLoadResponse -> response.episodes.filterValues { it.isNotEmpty() }
            else -> emptyMap()
        }
        val dubs = episodesByDub.keys.filterNotNull()
        val dub = dubs.firstOrNull()
        updateState {
            DetailState(
                status = DetailStatus.Done,
                response = response,
                movieData = when (response) {
                    is MovieLoadResponse -> response.dataUrl
                    is LiveStreamLoadResponse -> response.dataUrl
                    else -> null
                },
                dubs = dubs,
                selectedDub = dub,
                seasons = groupSeasons(response, episodesByDub[dub].orEmpty()),
                unsupported = if (response is TorrentLoadResponse) "Torrents cannot be played on desktop yet." else null,
            )
        }
    }

    /** What to play for an episode, or for the movie when [episode] is null */
    fun playRequest(episode: Episode? = null): PlayRequest? {
        val response = state.value.response ?: return null
        val data = episode?.data ?: state.value.movieData ?: return null
        return PlayRequest(api.name, response.name, episode?.let(::episodeLabel), data)
    }

    companion object {
        /** Groups episodes by season in season order, keeping the provider's order inside a season */
        fun groupSeasons(response: LoadResponse, episodes: List<Episode>): List<SeasonGroup> {
            if (episodes.isEmpty()) return emptyList()
            val names = (response as? EpisodeResponse)?.seasonNames.orEmpty()
            val bySeason = episodes.groupBy { it.season }
            if (bySeason.keys == setOf(null)) return listOf(SeasonGroup(null, "Episodes", episodes))
            return bySeason.entries
                .sortedBy { it.key ?: Int.MAX_VALUE }
                .map { (season, list) ->
                    val data = names.firstOrNull { it.season == season }
                    val label = if (season == null) "Other" else data?.name ?: "Season ${data?.displaySeason ?: season}"
                    SeasonGroup(season, label, list)
                }
        }

        /** For example "S1 E3 · Pilot" */
        fun episodeLabel(episode: Episode): String {
            val number = listOfNotNull(episode.season?.let { "S$it" }, episode.episode?.let { "E$it" }).joinToString(" ")
            return listOf(number, episode.name.orEmpty().trim()).filter { it.isNotEmpty() }.joinToString(" · ")
                .ifEmpty { "Episode" }
        }
    }
}
