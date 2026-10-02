package com.lagradost.cloudstream4.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.providers.filterProviders
import com.lagradost.cloudstream4.providers.searchSafely
import com.lagradost.cloudstream4.providers.withoutQualities
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

sealed interface ProviderStatus {
    data object Loading : ProviderStatus
    data object Done : ProviderStatus
    data class Failed(val message: String) : ProviderStatus
}

/** The results one provider returned for the current query */
data class ProviderResults(
    val apiName: String,
    val items: List<SearchResponse> = emptyList(),
    val status: ProviderStatus = ProviderStatus.Loading,
    val page: Int = 1,
    val hasNext: Boolean = false,
)

data class SearchState(
    /** What is typed in the search box */
    val query: String = "",
    /** The query the results below belong to, empty before the first search */
    val searchedQuery: String = "",
    val results: List<ProviderResults> = emptyList(),
    /** True when a search ran but no provider was enabled to search with */
    val noProviders: Boolean = false,
) {
    val isSearching: Boolean get() = results.any { it.status == ProviderStatus.Loading }
}

sealed interface SearchAction {
    data class QueryChanged(val query: String) : SearchAction
    /** Searches every enabled provider for the typed query */
    data object Submit : SearchAction
    /** Fetches the next page from one provider */
    data class LoadMore(val apiName: String) : SearchAction
    data object Clear : SearchAction
}

/**
 * Searches all enabled providers at once. Each provider fills in its own row as soon as it
 * answers, so a slow provider never holds back the others.
 *
 * @param apis all loaded providers, filtered here by the provider settings on every search
 */
class SearchViewModel(
    private val apis: () -> List<MainAPI>,
    private val settings: AppSettings,
) : ViewModel(), StateContainer<SearchState> by DefaultStateContainer(SearchState()),
    ActionHandler<SearchAction> {

    /** Parent of every request for the current query, cancelled when a new search starts */
    private var searchJob: Job = SupervisorJob()
    /** Increases with every search, so a late answer for an old query is dropped */
    private var generation = 0
    private var searched: List<MainAPI> = emptyList()

    override fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> updateState { copy(query = action.query) }
            SearchAction.Submit -> search(state.value.query.trim())
            is SearchAction.LoadMore -> loadMore(action.apiName)
            SearchAction.Clear -> {
                cancel()
                updateState { SearchState() }
            }
        }
    }

    private fun cancel() {
        searchJob.cancel()
        generation++
    }

    private fun search(query: String) {
        cancel()
        if (query.isEmpty()) {
            updateState { copy(searchedQuery = "", results = emptyList(), noProviders = false) }
            return
        }
        val provider = settings.provider
        searched = filterProviders(
            apis(),
            languages = provider.extensionLanguages.get(),
            preferredMedia = provider.preferredMedia.get(),
            requireMainPage = false,
        )
        updateState {
            copy(
                searchedQuery = query,
                results = searched.map { ProviderResults(it.name) },
                noProviders = searched.isEmpty(),
            )
        }
        searchJob = SupervisorJob(viewModelScope.coroutineContext.job)
        searched.forEach { fetch(it, query, page = 1) }
    }

    private fun loadMore(apiName: String) {
        val current = state.value.results.firstOrNull { it.apiName == apiName } ?: return
        if (!current.hasNext || current.status == ProviderStatus.Loading) return
        val api = searched.firstOrNull { it.name == apiName } ?: return
        updateRow(generation, apiName) { copy(status = ProviderStatus.Loading) }
        fetch(api, state.value.searchedQuery, current.page + 1)
    }

    private fun fetch(api: MainAPI, query: String, page: Int) {
        val forGeneration = generation
        viewModelScope.launch(searchJob) {
            val result = api.searchSafely(query, page)
            val hidden = settings.ui.filterQuality.get()
            updateRow(forGeneration, api.name) {
                when (result) {
                    is Resource.Success -> copy(
                        items = (if (page == 1) emptyList() else items) + result.value.items.withoutQualities(hidden),
                        status = ProviderStatus.Done,
                        page = page,
                        hasNext = result.value.hasNext,
                    )

                    is Resource.Failure -> copy(status = ProviderStatus.Failed(result.errorString))
                    is Resource.Loading -> this
                }
            }
        }
    }

    private fun updateRow(forGeneration: Int, apiName: String, reducer: ProviderResults.() -> ProviderResults) {
        updateState {
            // Checked inside the update, so a search started meanwhile always wins
            if (forGeneration != generation) return@updateState this
            copy(results = results.map { if (it.apiName == apiName) it.reducer() else it })
        }
    }
}
