package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * The Android app's Plugin, which Android-only extensions extend. Same members as the app, except
 * registerVideoClickAction: video click actions open other Android apps, which desktop cannot.
 */
abstract class Plugin : BasePlugin() {
    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /** Extension resources are Android resources, which desktop cannot read, so this stays null */
    var resources: Resources? = null

    /** Settings screens are Android views, so desktop does not show this button */
    var openSettings: ((context: Context) -> Unit)? = null
}
