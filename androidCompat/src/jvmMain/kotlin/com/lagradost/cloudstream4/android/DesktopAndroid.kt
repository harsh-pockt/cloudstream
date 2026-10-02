package com.lagradost.cloudstream4.android

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories

/**
 * The desktop side of the Android stand-ins: where extensions keep their files and settings, the
 * one application and activity they see, and how their toasts reach the app.
 */
object DesktopAndroid {
    const val PACKAGE_NAME = "com.lagradost.cloudstream3"

    @Volatile
    private var root: Path = Path.of(System.getProperty("java.io.tmpdir"), "cloudstream-android")

    /** Sets the folder extensions keep their files and settings in. Call before loading extensions */
    fun init(dataDir: Path) {
        root = dataDir
    }

    val filesDir: Path get() = root.resolve("files").also { it.createDirectories() }
    val cacheDir: Path get() = root.resolve("cache").also { it.createDirectories() }
    private val prefsDir: Path get() = root.resolve("shared_prefs").also { it.createDirectories() }

    private val preferences = ConcurrentHashMap<String, DesktopSharedPreferences>()

    /** The same instance for the same name, like Android, so listeners and unsaved edits are shared */
    fun sharedPreferences(name: String): SharedPreferences =
        preferences.computeIfAbsent(name) { DesktopSharedPreferences(prefsDir.resolve("${safeFileName(it)}.json")) }

    val application: Application by lazy { Application() }
    val activity: Activity by lazy { Activity() }

    /** Shows an extension's toast in the app. Without one, toasts are only logged */
    @Volatile
    var toastHandler: ((String) -> Unit)? = null

    fun showToast(message: String) {
        println("INFO Extension toast: $message")
        toastHandler?.invoke(message)
    }

    /** Opens a web link the way Android's ACTION_VIEW would. Returns false when nothing could open it */
    fun openUrl(url: String): Boolean = runCatching {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
        Desktop.getDesktop().browse(URI(url))
        true
    }.getOrDefault(false)

    private fun safeFileName(name: String) = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
}
