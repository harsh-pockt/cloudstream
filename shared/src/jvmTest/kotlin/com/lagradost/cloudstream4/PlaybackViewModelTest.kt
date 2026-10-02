package com.lagradost.cloudstream4

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTorrentLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.detail.DetailAction
import com.lagradost.cloudstream4.detail.DetailStatus
import com.lagradost.cloudstream4.detail.DetailViewModel
import com.lagradost.cloudstream4.detail.PlayRequest
import com.lagradost.cloudstream4.player.LinksAction
import com.lagradost.cloudstream4.player.LinksViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Serves a movie, a series, an anime and a torrent, and links for each, from memory */
private class FakeCatalog : MainAPI() {
    override var name = "Catalog"
    override var mainUrl = "https://example.invalid"

    /** loadLinks waits for this before sending the links listed after [slowAfter] */
    var gate: CompletableDeferred<Unit>? = null
    var slowAfter = Int.MAX_VALUE
    var links: List<Pair<String, Int>> = emptyList()
    var torrents = 0

    private fun ep(data: String, season: Int?, number: Int, title: String? = null) =
        newEpisode(data, initializer = { this.season = season; this.episode = number; this.name = title }, fix = false)

    override suspend fun load(url: String): LoadResponse = when (url.substringAfterLast('/')) {
        "movie" -> newMovieLoadResponse("A movie", url, TvType.Movie, "movie-data") { plot = "Plot"; tags = listOf("Drama", " ") }
        "series" -> newTvSeriesLoadResponse(
            "A series", url, TvType.TvSeries,
            listOf(ep("s2e1", 2, 1), ep("s1e1", 1, 1, "Pilot"), ep("s1e2", 1, 2), ep("extra", null, 1)),
        ) { addSeasonNames(listOf(SeasonData(1, "The beginning"), SeasonData(2, displaySeason = 20))) }

        "anime" -> newAnimeLoadResponse("An anime", url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, listOf(ep("sub1", null, 1), ep("sub2", null, 2)))
            addEpisodes(DubStatus.Dubbed, listOf(ep("dub1", null, 1)))
        }

        "torrent" -> newTorrentLoadResponse("A torrent", url, magnet = "magnet:?xt=x")
        else -> throw IllegalStateException("not found")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        subtitleCallback(newSubtitleFile("English", "https://example.invalid/en.vtt"))
        subtitleCallback(newSubtitleFile("English", "https://example.invalid/en.vtt"))
        repeat(torrents) { callback(newExtractorLink("T", "Torrent", "magnet:?xt=$it", ExtractorLinkType.MAGNET)) }
        links.forEachIndexed { index, (name, quality) ->
            if (index == slowAfter) gate?.await()
            callback(newExtractorLink(name, name, "https://example.invalid/$name.m3u8", ExtractorLinkType.M3U8) { this.quality = quality })
        }
        return links.isNotEmpty()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {
    private val api = FakeCatalog()

    // Not a test dispatcher: the links view model waits in real time before picking a link
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { flow.first(predicate) } }

    @Test
    fun movieGetsAPlayRequestAndBlankTagsAreDropped() {
        val vm = DetailViewModel(api, "https://example.invalid/movie")
        val state = await(vm.state) { it.status == DetailStatus.Done }
        assertEquals("movie-data", state.movieData)
        assertEquals(listOf("Drama"), state.response!!.tags)
        assertTrue(state.seasons.isEmpty())
        assertEquals(PlayRequest("Catalog", "A movie", null, "movie-data"), vm.playRequest())
    }

    @Test
    fun seriesEpisodesAreGroupedBySeasonWithTheirNames() {
        val vm = DetailViewModel(api, "https://example.invalid/series")
        val state = await(vm.state) { it.status == DetailStatus.Done }
        assertEquals(listOf("The beginning", "Season 20", "Other"), state.seasons.map { it.label })
        assertEquals(listOf("s1e1", "s1e2"), state.seasons[0].episodes.map { it.data })

        val pilot = state.seasons[0].episodes[0]
        assertEquals(PlayRequest("Catalog", "A series", "S1 E1 · Pilot", "s1e1"), vm.playRequest(pilot))

        vm.onAction(DetailAction.SelectSeason(1))
        assertEquals(1, vm.state.value.selectedSeason)
        vm.onAction(DetailAction.SelectSeason(99))
        assertEquals(2, vm.state.value.selectedSeason)
    }

    @Test
    fun animeEpisodesCanBeSwitchedBetweenSubbedAndDubbed() {
        val vm = DetailViewModel(api, "https://example.invalid/anime")
        val state = await(vm.state) { it.status == DetailStatus.Done }
        assertEquals(listOf(DubStatus.Subbed, DubStatus.Dubbed), state.dubs)
        assertEquals(listOf("sub1", "sub2"), state.seasons.single().episodes.map { it.data })
        assertEquals("Episodes", state.seasons.single().label)

        vm.onAction(DetailAction.SelectDub(DubStatus.Dubbed))
        assertEquals(listOf("dub1"), vm.state.value.seasons.single().episodes.map { it.data })
        assertEquals("E1", DetailViewModel.episodeLabel(vm.state.value.seasons.single().episodes.single()))
    }

    @Test
    fun torrentsAreMarkedUnsupportedAndFailuresCanBeRetried() {
        val torrent = await(DetailViewModel(api, "https://example.invalid/torrent").state) { it.status == DetailStatus.Done }
        assertTrue(torrent.unsupported != null)

        val missing = DetailViewModel(api, "https://example.invalid/missing")
        assertIs<DetailStatus.Failed>(await(missing.state) { it.status is DetailStatus.Failed }.status)
        missing.onAction(DetailAction.Retry)
        assertIs<DetailStatus.Failed>(await(missing.state) { it.status is DetailStatus.Failed }.status)
    }

    private fun links(vararg links: Pair<String, Int>, autoPickMs: Long = 50): LinksViewModel {
        api.links = links.toList()
        return LinksViewModel(api, PlayRequest("Catalog", "A movie", null, "movie-data"), autoPickMs.milliseconds)
    }

    @Test
    fun theBestLinkIsPickedAndTorrentsAreSkipped() {
        api.torrents = 2
        val vm = links("low" to Qualities.P360.value, "unknown" to Qualities.Unknown.value, "hd" to Qualities.P1080.value, "sd" to Qualities.P720.value)
        val state = await(vm.state) { !it.loading }

        assertEquals(listOf("hd", "sd", "unknown", "low"), state.links.map { it.name })
        assertEquals("hd", state.selectedLink?.name)
        assertEquals(2, state.skipped)
        assertEquals(1, state.subtitles.size)
        assertEquals("hd · 1080p", LinksViewModel.label(state.links[0]))
    }

    @Test
    fun aFailedLinkMovesOnToTheNextUntilNoneAreLeft() {
        val vm = links("a" to 1080, "b" to 720)
        await(vm.state) { !it.loading }

        vm.onAction(LinksAction.Failed("https://example.invalid/a.m3u8", "403"))
        assertEquals("b", vm.state.value.selectedLink?.name)
        // A late failure report for a link no longer playing changes nothing
        vm.onAction(LinksAction.Failed("https://example.invalid/a.m3u8", "403"))
        assertEquals("b", vm.state.value.selectedLink?.name)

        vm.onAction(LinksAction.Failed("https://example.invalid/b.m3u8", "timeout"))
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.exhausted)

        vm.onAction(LinksAction.Select("https://example.invalid/a.m3u8"))
        assertEquals("a", vm.state.value.selectedLink?.name)
    }

    @Test
    fun aSlowExtractorDoesNotHoldBackPlayback() {
        api.gate = CompletableDeferred()
        api.slowAfter = 1
        val vm = links("fast" to 480, "slow" to 1080)

        // The fast link plays while the slow extractor is still working
        val early = await(vm.state) { it.selected != null }
        assertTrue(early.loading)
        assertEquals("fast", early.selectedLink?.name)

        api.gate!!.complete(Unit)
        val done = await(vm.state) { !it.loading }
        assertEquals(listOf("slow", "fast"), done.links.map { it.name })
        // The better link arriving later does not interrupt what is playing
        assertEquals("fast", done.selectedLink?.name)
    }

    @Test
    fun noLinksMeansExhausted() {
        val vm = links()
        val state = await(vm.state) { !it.loading }
        assertNull(state.selected)
        assertTrue(state.exhausted)
    }
}
