package com.lagradost.cloudstream4.search

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream4.android.DesktopAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Search history in the Android app's key and JSON, "0/search_history/<hash of the query>", next to
 * the library in the same store.
 */
class DataStoreSearchHistory(
    private val context: Context = DesktopAndroid.application,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SearchHistoryStore {
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version.asStateFlow()

    private fun changed() = _version.update { it + 1 }

    /** The Android app's SearchHistoryItem */
    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SearchHistoryItem(
        @JsonProperty("searchedAt") val searchedAt: Long,
        @JsonProperty("searchText") val searchText: String,
        @JsonProperty("type") val type: List<TvType> = emptyList(),
        @JsonProperty("key") val key: String,
    )

    override fun entries(): List<SearchHistoryEntry> = synchronized(this) {
        context.getKeys("$ACCOUNT/$SEARCH_HISTORY")
            .mapNotNull { context.getKey(it, SearchHistoryItem::class.java) }
            .map { SearchHistoryEntry(it.key, it.searchText, it.searchedAt) }
            .sortedByDescending { it.searchedAt }
    }

    override fun add(query: String) {
        if (!SearchHistoryStore.isSaved(query)) return
        val key = SearchHistoryStore.keyOf(query)
        synchronized(this) { context.setKey("$ACCOUNT/$SEARCH_HISTORY/$key", SearchHistoryItem(clock(), query, emptyList(), key)) }
        changed()
    }

    override fun remove(key: String) {
        synchronized(this) { context.removeKey("$ACCOUNT/$SEARCH_HISTORY/$key") }
        changed()
    }

    override fun clear() {
        synchronized(this) { context.getKeys("$ACCOUNT/$SEARCH_HISTORY").forEach { context.removeKey(it) } }
        changed()
    }

    companion object {
        /** The Android app's first account, DataStoreHelper.currentAccount */
        private const val ACCOUNT = "0"
        private const val SEARCH_HISTORY = "search_history"

        val instance: DataStoreSearchHistory by lazy { DataStoreSearchHistory() }
    }
}
