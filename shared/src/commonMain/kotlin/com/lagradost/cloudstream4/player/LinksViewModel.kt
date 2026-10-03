package com.lagradost.cloudstream4.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.detail.PlayRequest
import com.lagradost.cloudstream4.providers.loadLinksSafely
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class LinksState(
    /** Playable links, best quality first */
    val links: List<ExtractorLink> = emptyList(),
    val subtitles: List<SubtitleFile> = emptyList(),
    val loading: Boolean = true,
    /** The url of the link being played */
    val selected: String? = null,
    /** Links that failed to play, by url, with the reason */
    val failed: Map<String, String> = emptyMap(),
    /** Links found that the player cannot use, such as DRM, or torrents where they cannot play */
    val skipped: Int = 0,
) {
    val selectedLink: ExtractorLink? get() = links.firstOrNull { it.url == selected }

    /** Loading finished and nothing is left to try */
    val exhausted: Boolean get() = !loading && links.none { it.url !in failed }
}

sealed interface LinksAction {
    data class Select(val url: String) : LinksAction
    /** The player could not play this link, so the next one is tried */
    data class Failed(val url: String, val reason: String) : LinksAction
    data object Retry : LinksAction
}

/**
 * Finds the links of one movie or episode and picks which to play: the best one once all links are
 * in, or the best so far when links keep coming after [autoPickAfter]. A link that fails to play
 * moves on to the next best one.
 */
class LinksViewModel(
    private val api: MainAPI,
    val request: PlayRequest,
    private val autoPickAfter: Duration = 6.seconds,
    /** Torrents and magnet links can play, after every other link */
    private val torrents: Boolean = false,
) : ViewModel(), StateContainer<LinksState> by DefaultStateContainer(LinksState()), ActionHandler<LinksAction> {

    private var loadJob: Job? = null
    /** Set once a link was picked automatically: from then on a new link plays if nothing else is */
    @kotlin.concurrent.Volatile
    private var autoPicking = false

    init {
        load()
    }

    override fun onAction(action: LinksAction) {
        when (action) {
            is LinksAction.Select -> updateState { copy(selected = action.url) }
            is LinksAction.Failed -> updateState {
                val failed = failed + (action.url to action.reason)
                // Only move on if the failed link is still the one playing
                copy(failed = failed, selected = if (selected == action.url) nextLink(links, failed) else selected)
            }

            LinksAction.Retry -> load()
        }
    }

    private fun load() {
        loadJob?.cancel()
        autoPicking = false
        updateState { LinksState() }
        loadJob = viewModelScope.launch {
            val picker = launch {
                // Some extractors take minutes, so do not wait for all of them once a link is in
                state.first { it.links.isNotEmpty() }
                delay(autoPickAfter)
                pickIfNone()
            }
            // A torrent title's data is its magnet or .torrent link, which the extension may not repeat
            if (torrents) torrentLink(request.data)?.let(::addLink)
            api.loadLinksSafely(
                request.data,
                onSubtitle = { sub -> updateState { if (subtitles.any { it.url == sub.url }) this else copy(subtitles = subtitles + sub) } },
                onLink = ::addLink,
            )
            picker.cancel()
            // One update, so nobody sees loading finished with nothing picked yet
            autoPicking = true
            updateState { copy(loading = false, selected = selected ?: nextLink(links, failed)) }
        }
    }

    private fun addLink(link: ExtractorLink) {
        val playable = link !is DrmExtractorLink && (torrents || !isTorrent(link))
        updateState {
            when {
                !playable -> copy(skipped = skipped + 1)
                links.any { it.url == link.url } -> this
                else -> {
                    // Torrents last: they take a while to start and share the user's connection
                    val links = (links + link).sortedWith(compareBy<ExtractorLink> { isTorrent(it) }.thenByDescending { rank(it.quality) })
                    copy(links = links, selected = selected ?: if (autoPicking) nextLink(links, failed) else null)
                }
            }
        }
    }

    private fun pickIfNone() {
        autoPicking = true
        updateState { if (selected != null) this else copy(selected = nextLink(links, failed)) }
    }

    companion object {
        private fun nextLink(links: List<ExtractorLink>, failed: Map<String, String>) =
            links.firstOrNull { it.url !in failed }?.url

        fun isTorrent(link: ExtractorLink) = link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET

        /** The link a torrent title's data is, as the Android app plays it */
        private fun torrentLink(data: String): ExtractorLink? {
            val type = when {
                data.startsWith("magnet:") -> ExtractorLinkType.MAGNET
                data.substringBefore('?').endsWith(".torrent") -> ExtractorLinkType.TORRENT
                else -> return null
            }
            @Suppress("DEPRECATION")
            return ExtractorLink("Torrent", "Torrent", data, "", Qualities.Unknown.value, type = type)
        }

        /** Higher is better. An unknown quality ranks with 480p, as on Android */
        fun rank(quality: Int): Int = if (quality == Qualities.Unknown.value) Qualities.P480.value else quality

        /** For example "Vidplay · 1080p" */
        fun label(link: ExtractorLink): String =
            listOf(link.name, Qualities.getStringByInt(link.quality)).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
}
