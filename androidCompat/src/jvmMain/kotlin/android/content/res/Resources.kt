package android.content.res

import android.util.DisplayMetrics

/**
 * Android's Resources for the screen metrics and configuration extensions read. Desktop has no app
 * resources, so lookups find nothing: identifiers are 0 and strings are empty.
 */
open class Resources {
    private val metrics = DisplayMetrics()
    private val configuration = Configuration()

    open fun getDisplayMetrics(): DisplayMetrics = metrics
    open fun getConfiguration(): Configuration = configuration
    open fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0
    open fun getString(id: Int): String = ""
    open fun getString(id: Int, vararg formatArgs: Any?): String = ""
    open fun getColor(id: Int): Int = 0
    open fun getDimension(id: Int): Float = 0f
    open fun getDimensionPixelSize(id: Int): Int = 0
    open fun getBoolean(id: Int): Boolean = false
    open fun getInteger(id: Int): Int = 0

    open class NotFoundException(name: String?) : RuntimeException(name) {
        constructor() : this(null)
    }

    companion object {
        private val system = Resources()

        @JvmStatic
        fun getSystem(): Resources = system
    }
}

open class Configuration {
    @JvmField var uiMode = UI_MODE_TYPE_NORMAL or UI_MODE_NIGHT_YES
    @JvmField var orientation = ORIENTATION_LANDSCAPE
    @JvmField var screenLayout = SCREENLAYOUT_SIZE_LARGE
    @JvmField var fontScale = 1f
    @JvmField var densityDpi = 160
    @JvmField var screenWidthDp = 1280
    @JvmField var screenHeightDp = 720
    @JvmField var smallestScreenWidthDp = 720
    @JvmField var locale: java.util.Locale = java.util.Locale.getDefault()

    companion object {
        const val UI_MODE_TYPE_MASK = 0x0f
        const val UI_MODE_TYPE_NORMAL = 0x01
        const val UI_MODE_TYPE_TELEVISION = 0x04
        const val UI_MODE_NIGHT_MASK = 0x30
        const val UI_MODE_NIGHT_NO = 0x10
        const val UI_MODE_NIGHT_YES = 0x20
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val SCREENLAYOUT_SIZE_MASK = 0x0f
        const val SCREENLAYOUT_SIZE_LARGE = 0x03
    }
}
