package com.lagradost.cloudstream4.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A past search, keyed like the Android app: the query's hash, so searching it again moves it to the top */
data class SearchHistoryEntry(
    val key: String,
    val query: String,
    val searchedAt: Long,
)

/** The searches the user ran, newest first */
interface SearchHistoryStore {
    /** Increases on every change, so screens know to read again */
    val version: StateFlow<Int>

    fun entries(): List<SearchHistoryEntry>
    fun add(query: String)
    fun remove(key: String)
    fun clear()

    companion object {
        /** The Android app's key for a query */
        fun keyOf(query: String) = query.hashCode().toString()

        /** The Android app saves searches of two or more letters */
        fun isSaved(query: String) = query.length > 1
    }
}

class InMemorySearchHistory(private val clock: () -> Long = { System.currentTimeMillis() }) : SearchHistoryStore {
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version.asStateFlow()
    private val entries = LinkedHashMap<String, SearchHistoryEntry>()

    private fun changed() = _version.update { it + 1 }

    override fun entries() = entries.values.sortedByDescending { it.searchedAt }

    override fun add(query: String) {
        if (!SearchHistoryStore.isSaved(query)) return
        val key = SearchHistoryStore.keyOf(query)
        entries[key] = SearchHistoryEntry(key, query, clock())
        changed()
    }

    override fun remove(key: String) {
        entries.remove(key)
        changed()
    }

    override fun clear() {
        entries.clear()
        changed()
    }
}
