package android.app

import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import com.lagradost.cloudstream4.android.DesktopAndroid

/**
 * The one activity extensions see on desktop, from CommonActivity.activity. It has no window, so
 * extension screens that need one do nothing.
 */
open class Activity : ContextThemeWrapper() {
    private var intent: Intent? = Intent(Intent.ACTION_MAIN)
    private var requestedOrientation = -1

    open fun runOnUiThread(action: Runnable?) {
        if (action == null) return
        if (Looper.getMainLooper().isCurrentThread()) action.run() else Handler(Looper.getMainLooper()).post(action)
    }

    open fun getApplication(): Application = DesktopAndroid.application
    open fun getIntent(): Intent? = intent
    open fun setIntent(newIntent: Intent?) {
        intent = newIntent
    }

    open fun finish() {}
    open fun recreate() {}
    open fun isFinishing(): Boolean = false
    open fun isDestroyed(): Boolean = false
    open fun getRequestedOrientation(): Int = requestedOrientation
    open fun setRequestedOrientation(orientation: Int) {
        requestedOrientation = orientation
    }
}

open class Application : ContextWrapper() {
    open fun onCreate() {}
}
