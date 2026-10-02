package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream4.android.DesktopAndroid

/** The Android app's Application, for the context and settings helpers in its companion */
class CloudStreamApp : Application() {
    companion object {
        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> {
                    val base = getBaseContext() ?: return null
                    if (base === this) null else base.getActivity()
                }
                else -> null
            }
        }

        val context: Context? get() = DesktopAndroid.application

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** Desktop has no WebView, so the link always opens in the browser */
        fun openBrowser(url: String, activity: Activity?) {
            DesktopAndroid.openUrl(url)
        }
    }
}

/** Deprecated alias for CloudStreamApp that older extensions still use */
class AcraApplication {
    companion object {
        val context get() = CloudStreamApp.context

        fun removeKeys(folder: String): Int? = CloudStreamApp.removeKeys(folder)

        fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)

        fun <T> setKey(folder: String, path: String, value: T) = CloudStreamApp.setKey(folder, path, value)

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = CloudStreamApp.getKey(path, defVal)

        inline fun <reified T : Any> getKey(path: String): T? = CloudStreamApp.getKey(path)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? = CloudStreamApp.getKey(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            CloudStreamApp.getKey(folder, path, defVal)
    }
}
