package android.content.pm

import android.content.ComponentName
import android.content.Intent
import com.lagradost.cloudstream4.android.DesktopAndroid

/** Android's PackageManager for the app restart and feature checks extensions do. Desktop has no other packages */
open class PackageManager {
    /** Only the app itself is found, so restart code gets an intent, which startActivity then ignores */
    open fun getLaunchIntentForPackage(packageName: String?): Intent? =
        if (packageName == DesktopAndroid.PACKAGE_NAME) {
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(packageName, "$packageName.MainActivity"))
        } else null

    open fun hasSystemFeature(name: String?): Boolean = false

    open class NameNotFoundException(name: String?) : Exception(name) {
        constructor() : this(null)
    }

    companion object {
        const val FEATURE_LEANBACK = "android.software.leanback"
        const val FEATURE_TOUCHSCREEN = "android.hardware.touchscreen"
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1

        internal val instance = PackageManager()
    }
}

open class ApplicationInfo {
    @JvmField var packageName: String? = DesktopAndroid.PACKAGE_NAME
    @JvmField var dataDir: String? = DesktopAndroid.filesDir.parent.toString()
    @JvmField var flags = 0
    @JvmField var targetSdkVersion = 35
    @JvmField var minSdkVersion = 21

    companion object {
        internal val instance by lazy { ApplicationInfo() }
    }
}
