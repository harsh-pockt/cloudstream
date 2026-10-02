package com.lagradost.cloudstream3

import android.app.Activity
import com.lagradost.cloudstream3.utils.Event

/**
 * The Android app's MainActivity, for the events in its companion that extensions subscribe to.
 * The desktop app fires afterPluginsLoadedEvent and mainPluginsLoadedEvent after loading extensions.
 */
class MainActivity : Activity() {
    companion object {
        const val TAG = "MAINACT"

        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
    }
}
