package com.lagradost.cloudstream4.home

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
import com.lagradost.cloudstream4.providers.mainPageSafely
import com.lagradost.cloudstream4.providers.withoutQualities
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One row on the home screen, a section of the provider's home page */
data class HomeRow(
    val name: String,
    val items: List<SearchResponse>,
    val horizontalImages: Boolean,
)

sealed interface HomeStatus {
    /** No provider with a home page is installed or enabled */
    data object NoProviders : HomeStatus
    data object Loading : HomeStatus
    data object Done : HomeStatus
    data class Failed(val message: String) : HomeStatus
}

data class HomeState(
    /** Names of the providers that have a home page and pass the provider settings */
    val providers: List<String> = emptyList(),
    val selected: String? = null,
    val rows: List<HomeRow> = emptyList(),
    val status: HomeStatus = HomeStatus.NoProviders,
)

sealed interface HomeAction {
    data class SelectProvider(val name: String) : HomeAction
    data object Reload : HomeAction
    /** Plugins were loaded or removed, or the provider settings changed */
    data object ProvidersChanged : HomeAction
}

/**
 * Shows the home page of one provider. The chosen provider is saved, and if it goes away
 * (uninstalled, or hidden by the provider settings) the first remaining one is shown instead.
 *
 * @param apis all loaded providers, filtered here by the provider settings
 */
class HomeViewModel(
    private val apis: () -> List<MainAPI>,
    private val settings: AppSettings,
) : ViewModel(), StateContainer<HomeState> by DefaultStateContainer(HomeState()),
    ActionHandler<HomeAction> {

    private var available: List<MainAPI> = emptyList()
    private var loadJob: Job? = null

    init {
        refreshProviders()
    }

    override fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.SelectProvider -> {
                val api = available.firstOrNull { it.name == action.name } ?: return
                settings.provider.homeProvider.set(api.name)
                load(api)
            }

            HomeAction.Reload -> available.firstOrNull { it.name == state.value.selected }?.let(::load)
            HomeAction.ProvidersChanged -> refreshProviders()
        }
    }

    private fun refreshProviders() {
        val provider = settings.provider
        available = filterProviders(
            apis(),
            languages = provider.extensionLanguages.get(),
            preferredMedia = provider.preferredMedia.get(),
            requireMainPage = true,
        )
        val names = available.map { it.name }
        updateState { copy(providers = names) }

        val saved = provider.homeProvider.get()
        val api = available.firstOrNull { it.name == saved } ?: available.firstOrNull()
        when {
            api == null -> {
                loadJob?.cancel()
                updateState { copy(selected = null, rows = emptyList(), status = HomeStatus.NoProviders) }
            }
            // Keep what is on screen if the shown provider is still there
            api.name == state.value.selected && state.value.status != HomeStatus.NoProviders -> Unit
            else -> load(api)
        }
    }

    private fun load(api: MainAPI) {
        loadJob?.cancel()
        updateState { copy(selected = api.name, rows = emptyList(), status = HomeStatus.Loading) }
        loadJob = viewModelScope.launch {
            val result = api.mainPageSafely(page = 1)
            // A different provider was picked while this one was loading
            if (state.value.selected != api.name) return@launch
            val hidden = settings.ui.filterQuality.get()
            updateState {
                when (result) {
                    is Resource.Success -> copy(
                        rows = result.value.filterNotNull().flatMap { it.items }
                            .map { HomeRow(it.name, it.list.withoutQualities(hidden), it.isHorizontalImages) }
                            .filter { it.items.isNotEmpty() },
                        status = HomeStatus.Done,
                    )

                    is Resource.Failure -> copy(status = HomeStatus.Failed(result.errorString))
                    is Resource.Loading -> this
                }
            }
        }
    }
}
