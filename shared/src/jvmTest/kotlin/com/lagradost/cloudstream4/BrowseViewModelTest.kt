package com.lagradost.cloudstream4

import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream4.home.HomeAction
import com.lagradost.cloudstream4.home.HomeStatus
import com.lagradost.cloudstream4.home.HomeViewModel
import com.lagradost.cloudstream4.plugins.DesktopPluginManager
import com.lagradost.cloudstream4.plugins.RepositoryClient
import com.lagradost.cloudstream4.providers.filterProviders
import com.lagradost.cloudstream4.search.InMemorySearchHistory
import com.lagradost.cloudstream4.search.ProviderStatus
import com.lagradost.cloudstream4.search.SearchAction
import com.lagradost.cloudstream4.search.SearchViewModel
import com.mihon.common.preference.JvmPreferenceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A provider that answers from memory, optionally waiting for [gate] first or failing */
private class FakeProvider(
    override var name: String,
    override var lang: String = "en",
    override val supportedTypes: Set<TvType> = setOf(TvType.Movie),
    override val hasMainPage: Boolean = true,
    private val pages: Int = 1,
    private val fail: Boolean = false,
) : MainAPI() {
    override var mainUrl = "https://example.invalid"
    override val mainPage = mainPageOf("popular" to "Popular", "new" to "New", "empty" to "Empty")
    var gate: CompletableDeferred<Unit>? = null
    val searchedPages = mutableListOf<Int>()

    fun item(title: String, quality: SearchQuality? = null) =
        newMovieSearchResponse(title, "https://example.invalid/$title") { this.quality = quality }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        gate?.await()
        if (fail) throw IllegalStateException("site is down")
        searchedPages += page
        return newSearchResponseList(
            listOf(item("$name $query $page"), item("$name cam $page", SearchQuality.Cam)),
            hasNext = page < pages,
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        gate?.await()
        if (fail) throw IllegalStateException("site is down")
        val items = if (request.data == "empty") emptyList() else listOf(item("$name ${request.name}"))
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = request.data == "new"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private val dir = Files.createTempDirectory("cs-browse")
    private val settings = AppSettings(JvmPreferenceStore(dir.resolve("settings.json")))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        dir.toFile().deleteRecursively()
    }

    /** Waits for real provider calls, which run on the worker threads */
    private fun <T> awaitState(flow: kotlinx.coroutines.flow.StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { flow.first(predicate) } }

    @Test
    fun providerFilterFollowsLanguageMediaAndHomePageSettings() {
        val en = FakeProvider("En")
        val hi = FakeProvider("Hi", lang = "hi")
        val anime = FakeProvider("Anime", supportedTypes = setOf(TvType.Anime))
        val noHome = FakeProvider("NoHome", hasMainPage = false)
        val all = listOf(en, hi, anime, noHome)
        val movie = setOf(TvType.Movie.ordinal.toString())

        assertEquals(all, filterProviders(all, setOf(AllLanguagesName), emptySet(), requireMainPage = false))
        assertEquals(listOf(hi), filterProviders(all, setOf("hi"), emptySet(), requireMainPage = false))
        assertEquals(listOf(en, hi, noHome), filterProviders(all, setOf(AllLanguagesName), movie, requireMainPage = false))
        assertEquals(listOf(en, hi, anime), filterProviders(all, setOf(AllLanguagesName), emptySet(), requireMainPage = true))
    }

    @Test
    fun searchFillsEachProviderAndKeepsFailuresSeparate() {
        val good = FakeProvider("Good")
        val broken = FakeProvider("Broken", fail = true)
        val vm = SearchViewModel({ listOf(good, broken) }, settings)

        vm.onAction(SearchAction.QueryChanged("  dune "))
        vm.onAction(SearchAction.Submit)
        val state = awaitState(vm.state) { !it.isSearching }

        assertEquals("dune", state.searchedQuery)
        val (goodRow, brokenRow) = state.results
        assertEquals(ProviderStatus.Done, goodRow.status)
        assertEquals(listOf("Good dune 1", "Good cam 1"), goodRow.items.map { it.name })
        assertIs<ProviderStatus.Failed>(brokenRow.status)
        assertTrue(brokenRow.items.isEmpty())
    }

    @Test
    fun hiddenQualitiesAreFilteredAndLoadMoreAppendsTheNextPage() {
        settings.ui.filterQuality.set(setOf(SearchQuality.Cam))
        val paged = FakeProvider("Paged", pages = 2)
        val vm = SearchViewModel({ listOf(paged) }, settings)

        vm.onAction(SearchAction.QueryChanged("x"))
        vm.onAction(SearchAction.Submit)
        val first = awaitState(vm.state) { !it.isSearching }.results.single()
        assertEquals(listOf("Paged x 1"), first.items.map { it.name })
        assertTrue(first.hasNext)

        vm.onAction(SearchAction.LoadMore("Paged"))
        val second = awaitState(vm.state) { it.results.single().page == 2 }.results.single()
        assertEquals(listOf("Paged x 1", "Paged x 2"), second.items.map { it.name })
        assertEquals(false, second.hasNext)

        // Nothing more to load, so no request is made
        vm.onAction(SearchAction.LoadMore("Paged"))
        assertEquals(listOf(1, 2), paged.searchedPages)
    }

    @Test
    fun aNewSearchDropsLateAnswersFromTheOldOne() {
        val slow = FakeProvider("Slow").apply { gate = CompletableDeferred() }
        val vm = SearchViewModel({ listOf(slow) }, settings)

        vm.onAction(SearchAction.QueryChanged("old"))
        vm.onAction(SearchAction.Submit)
        val oldGate = slow.gate!!
        slow.gate = null
        vm.onAction(SearchAction.QueryChanged("new"))
        vm.onAction(SearchAction.Submit)
        oldGate.complete(Unit)

        val state = awaitState(vm.state) { !it.isSearching }
        assertEquals(listOf("Slow new 1", "Slow cam 1"), state.results.single().items.map { it.name })
    }

    @Test
    fun searchesAreRememberedNewestFirstAndCanBeRunAgain() {
        var now = 0L
        val history = InMemorySearchHistory { ++now }
        val provider = FakeProvider("P")
        val vm = SearchViewModel({ listOf(provider) }, settings, history)

        listOf("dune", "x", "alien", "dune").forEach {
            vm.onAction(SearchAction.QueryChanged(it))
            vm.onAction(SearchAction.Submit)
        }
        // One letter is not saved, and searching "dune" again moves it to the top
        assertEquals(listOf("dune", "alien"), vm.state.value.history.map { it.query })

        vm.onAction(SearchAction.Clear)
        assertEquals(listOf("dune", "alien"), vm.state.value.history.map { it.query }, "clearing the box keeps the history")

        vm.onAction(SearchAction.SearchFor("alien"))
        val state = awaitState(vm.state) { !it.isSearching && it.searchedQuery == "alien" }
        assertEquals("alien", state.query)
        assertEquals(listOf("alien", "dune"), state.history.map { it.query })

        vm.onAction(SearchAction.RemoveHistory(state.history.first().key))
        assertEquals(listOf("dune"), vm.state.value.history.map { it.query })
        vm.onAction(SearchAction.ClearHistory)
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test
    fun searchWithNoEnabledProvidersSaysSo() {
        settings.provider.extensionLanguages.set(setOf("fr"))
        val vm = SearchViewModel({ listOf(FakeProvider("En")) }, settings)
        vm.onAction(SearchAction.QueryChanged("x"))
        vm.onAction(SearchAction.Submit)
        assertTrue(vm.state.value.noProviders)
    }

    @Test
    fun homeShowsTheFirstProviderAndDropsEmptyRows() {
        val vm = HomeViewModel({ listOf(FakeProvider("A"), FakeProvider("B")) }, settings)
        val state = awaitState(vm.state) { it.status == HomeStatus.Done }

        assertEquals(listOf("A", "B"), state.providers)
        assertEquals("A", state.selected)
        assertEquals(listOf("Popular", "New"), state.rows.map { it.name })
        assertEquals(listOf(false, true), state.rows.map { it.horizontalImages })
    }

    @Test
    fun theChosenHomeProviderIsSavedAndUsedNextTime() {
        val providers = listOf(FakeProvider("A"), FakeProvider("B"))
        val vm = HomeViewModel({ providers }, settings)
        vm.onAction(HomeAction.SelectProvider("B"))
        awaitState(vm.state) { it.selected == "B" && it.status == HomeStatus.Done }

        val restarted = AppSettings(JvmPreferenceStore(dir.resolve("settings.json")))
        val next = HomeViewModel({ providers }, restarted)
        assertEquals("B", awaitState(next.state) { it.status == HomeStatus.Done }.selected)
    }

    @Test
    fun homeWaitsForProvidersThenFallsBackWhenTheShownOneIsRemoved() {
        var providers = emptyList<MainAPI>()
        val vm = HomeViewModel({ providers }, settings)
        assertEquals(HomeStatus.NoProviders, vm.state.value.status)

        // Plugins finish loading after the screen opened
        providers = listOf(FakeProvider("A"), FakeProvider("B"))
        vm.onAction(HomeAction.SelectProvider("B")) // not known yet, ignored
        vm.onAction(HomeAction.ProvidersChanged)
        awaitState(vm.state) { it.selected == "A" && it.status == HomeStatus.Done }
        vm.onAction(HomeAction.SelectProvider("B"))
        awaitState(vm.state) { it.selected == "B" && it.status == HomeStatus.Done }

        providers = providers.take(1)
        vm.onAction(HomeAction.ProvidersChanged)
        assertEquals("A", awaitState(vm.state) { it.status == HomeStatus.Done && it.selected == "A" }.selected)
    }

    /**
     * Installs a real plugin from the official repository, then browses its home page and searches it.
     * Needs the internet, so it only runs with CLOUDSTREAM_ONLINE_TESTS=1.
     */
    @Test
    fun officialPluginWorksInHomeAndSearch() {
        if (System.getenv("CLOUDSTREAM_ONLINE_TESTS") != "1") return println("Skipped, set CLOUDSTREAM_ONLINE_TESTS=1")
        val client = RepositoryClient()
        val manager = DesktopPluginManager(dir.resolve("plugins"), client)
        val plugin = runBlocking {
            val repo = client.fetchRepository(RepositoryClient.OFFICIAL_REPOSITORY)
            client.fetchPlugins(repo).first { it.internalName == "InternetArchiveProvider" }.also {
                manager.install(repo.url, it)
            }
        }
        val providers = manager.loadedPlugin(plugin.internalName)!!.providers
        try {
            val home = HomeViewModel({ providers }, settings)
            val homeState = runBlocking { withTimeout(60_000) { home.state.first { it.status !is HomeStatus.Loading } } }
            println("Home ${homeState.selected}: ${homeState.status}, rows ${homeState.rows.map { "${it.name} (${it.items.size})" }}")
            assertEquals(HomeStatus.Done, homeState.status)
            assertTrue(homeState.rows.isNotEmpty())

            val search = SearchViewModel({ providers }, settings)
            search.onAction(SearchAction.QueryChanged("nosferatu"))
            search.onAction(SearchAction.Submit)
            val row = runBlocking { withTimeout(60_000) { search.state.first { !it.isSearching } } }.results.single()
            println("Search ${row.apiName}: ${row.status}, ${row.items.size} results, first ${row.items.firstOrNull()?.name}")
            assertTrue(row.items.isNotEmpty())
        } finally {
            runBlocking { manager.uninstall(plugin.internalName) }
        }
    }

    @Test
    fun aFailingHomePageShowsTheErrorAndCanBeRetried() {
        val vm = HomeViewModel({ listOf(FakeProvider("Down", fail = true)) }, settings)
        assertIs<HomeStatus.Failed>(awaitState(vm.state) { it.status is HomeStatus.Failed }.status)
        vm.onAction(HomeAction.Reload)
        assertIs<HomeStatus.Failed>(awaitState(vm.state) { it.status is HomeStatus.Failed }.status)
    }
}
