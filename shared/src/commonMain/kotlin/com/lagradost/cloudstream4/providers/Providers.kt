package com.lagradost.cloudstream4.providers

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newSearchResponseList
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The providers the user wants to see, the same rules as filterProviderByPreferredMedia in the
 * Android app: the provider language must be selected, unless all languages are, and it must
 * support at least one preferred media type, unless none are selected.
 *
 * @param preferredMedia TvType ordinals as strings, as the prefer_media_type_key_2 setting stores them
 */
fun filterProviders(
    apis: List<MainAPI>,
    languages: Set<String>,
    preferredMedia: Set<String>,
    requireMainPage: Boolean,
): List<MainAPI> {
    val allLanguages = languages.isEmpty() || languages.contains(AllLanguagesName)
    val media = preferredMedia.mapNotNull { it.toIntOrNull() }.toSet()
    return apis.filter { api ->
        (allLanguages || languages.contains(api.lang)) &&
                (api.hasMainPage || !requireMainPage) &&
                (media.isEmpty() || api.supportedTypes.any { it.ordinal in media })
    }
}

/** Drops results whose quality the user chose to hide in settings */
fun List<SearchResponse>.withoutQualities(hidden: Set<SearchQuality>): List<SearchResponse> =
    if (hidden.isEmpty()) this else filter { it.quality !in hidden }

/*
 * Calls into providers with the same limits as APIRepository in the Android app, so one slow or
 * broken provider can never hang a screen. Errors come back as Resource.Failure instead of throwing.
 */

private const val DEFAULT_TIMEOUT = 120_000L
private const val MAX_TIMEOUT = 4 * DEFAULT_TIMEOUT
private const val MIN_TIMEOUT = 5_000L

internal fun providerTimeout(desired: Long?): Long = (desired ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

/** Paged search, the first page is 1 */
suspend fun MainAPI.searchSafely(query: String, page: Int): Resource<SearchResponseList> {
    if (query.isBlank()) return Resource.Success(newSearchResponseList(emptyList()))
    return safeApiCall {
        withTimeout(providerTimeout(searchTimeoutMs)) {
            search(query, page) ?: throw ErrorLoadingException()
        }
    }
}

/** Every home page section of this provider, the first page is 1 */
suspend fun MainAPI.mainPageSafely(page: Int): Resource<List<HomePageResponse?>> = safeApiCall {
    withTimeout(providerTimeout(getMainPageTimeoutMs)) {
        lastHomepageRequest = APIHolder.unixTimeMS
        val requests = mainPage.map { MainPageRequest(it.name, it.data, it.horizontalImages) }
        if (sequentialMainPage) {
            requests.mapIndexed { index, request ->
                // Some sites block many requests at once, so these providers ask for a pause in between
                if (index > 0) delay(sequentialMainPageDelay)
                getMainPage(page, request)
            }
        } else {
            requests.amap { getMainPage(page, it) }
        }
    }
}
