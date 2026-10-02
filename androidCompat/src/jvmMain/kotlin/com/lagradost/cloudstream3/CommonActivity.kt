package com.lagradost.cloudstream3

import android.app.Activity
import android.content.res.Resources
import android.util.DisplayMetrics
import com.lagradost.cloudstream4.android.DesktopAndroid
import kotlin.math.max
import kotlin.math.min

/** The Android app's CommonActivity, for the activity and toasts extensions use */
object CommonActivity {
    val activity: Activity? get() = DesktopAndroid.activity

    fun setActivityInstance(newActivity: Activity?) {}

    val displayMetrics: DisplayMetrics = Resources.getSystem().getDisplayMetrics()

    val screenWidth: Int get() = max(displayMetrics.widthPixels, displayMetrics.heightPixels)
    val screenHeight: Int get() = min(displayMetrics.widthPixels, displayMetrics.heightPixels)
    val screenWidthWithOrientation: Int get() = displayMetrics.widthPixels
    val screenHeightWithOrientation: Int get() = displayMetrics.heightPixels

    var isPipDesired: Boolean = false
    var isInPIPMode: Boolean = false
    var appliedTheme: Int = 0
    var appliedColor: Int = 0

    const val TAG = "COMPACT"

    /** Desktop has no string resources, so these show nothing */
    fun showToast(message: Int, duration: Int? = null) {}

    fun showToast(message: String?, duration: Int? = null) {
        message?.let(DesktopAndroid::showToast)
    }

    fun showToast(act: Activity?, message: Int, duration: Int? = null) {}

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        message?.let(DesktopAndroid::showToast)
    }
}
