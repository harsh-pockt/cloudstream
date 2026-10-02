package android.util

/** A 1280x720 screen at Android's baseline density, so dp and px are the same */
open class DisplayMetrics {
    @JvmField var widthPixels = 1280
    @JvmField var heightPixels = 720
    @JvmField var density = 1f
    @JvmField var densityDpi = 160
    @JvmField var scaledDensity = 1f
    @JvmField var xdpi = 160f
    @JvmField var ydpi = 160f

    open fun setToDefaults() {}

    companion object {
        const val DENSITY_DEFAULT = 160
    }
}
