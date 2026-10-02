package android.content

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.Looper
import com.lagradost.cloudstream4.android.DesktopAndroid
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android's Context with the parts extensions use: settings, folders, the package name and opening
 * links. Everything else is missing, so an extension calling it fails with NoSuchMethodError only
 * when that code runs.
 */
abstract class Context {
    open fun getApplicationContext(): Context = DesktopAndroid.application

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        DesktopAndroid.sharedPreferences(name ?: "null")

    open fun getResources(): Resources = Resources.getSystem()
    open fun getPackageName(): String = DesktopAndroid.PACKAGE_NAME
    open fun getPackageManager(): PackageManager = PackageManager.instance
    open fun getApplicationInfo(): ApplicationInfo = ApplicationInfo.instance
    open fun getMainLooper(): Looper = Looper.getMainLooper()
    open fun getClassLoader(): ClassLoader = javaClass.classLoader

    open fun getFilesDir(): File = DesktopAndroid.filesDir.toFile()
    open fun getCacheDir(): File = DesktopAndroid.cacheDir.toFile()
    open fun getDataDir(): File = DesktopAndroid.filesDir.parent.toFile()
    open fun getExternalFilesDir(type: String?): File? = if (type == null) getFilesDir() else File(getFilesDir(), type).apply { mkdirs() }
    open fun getExternalCacheDir(): File? = getCacheDir()
    open fun getDir(name: String?, mode: Int): File = File(getDataDir(), "app_$name").apply { mkdirs() }
    open fun openFileInput(name: String?): FileInputStream = FileInputStream(File(getFilesDir(), name ?: ""))
    open fun openFileOutput(name: String?, mode: Int): FileOutputStream =
        FileOutputStream(File(getFilesDir(), name ?: ""), mode and MODE_APPEND != 0)
    open fun deleteFile(name: String?): Boolean = File(getFilesDir(), name ?: "").delete()

    /** Strings come from the app's resources on Android, which desktop does not have */
    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg formatArgs: Any?): String = ""
    open fun getColor(id: Int): Int = 0

    /** No Android services exist on desktop */
    open fun getSystemService(name: String?): Any? = null

    /** Opens web links in the browser, which is what extensions use this for. Other intents are ignored */
    open fun startActivity(intent: Intent?) {
        val url = intent?.getData()?.toString()
        if (intent?.getAction() == Intent.ACTION_VIEW && url != null && DesktopAndroid.openUrl(url)) return
        println("WARNING Context: Ignored startActivity($intent), desktop has no Android activities")
    }

    open fun startActivity(intent: Intent?, options: Bundle?) = startActivity(intent)
    open fun sendBroadcast(intent: Intent?) {}

    companion object {
        const val MODE_PRIVATE = 0
        const val MODE_WORLD_READABLE = 1
        const val MODE_WORLD_WRITEABLE = 2
        const val MODE_APPEND = 32768
        const val ACTIVITY_SERVICE = "activity"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val LAYOUT_INFLATER_SERVICE = "layout_inflater"
        const val NOTIFICATION_SERVICE = "notification"
        const val UI_MODE_SERVICE = "uimode"
        const val WINDOW_SERVICE = "window"
    }
}

open class ContextWrapper(private var base: Context?) : Context() {
    /** Generated stub subclasses call the no-argument constructor */
    constructor() : this(null)

    open fun getBaseContext(): Context? = base

    protected open fun attachBaseContext(base: Context?) {
        this.base = base
    }
}
