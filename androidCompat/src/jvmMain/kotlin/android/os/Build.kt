package android.os

/** Describes a recent Android phone, so extensions take their modern code paths */
open class Build {
    open class VERSION {
        companion object {
            @JvmField val SDK_INT: Int = 35
            @JvmField val RELEASE: String = "15"
            @JvmField val CODENAME: String = "REL"
            @JvmField val INCREMENTAL: String = "desktop"
            @JvmField val PREVIEW_SDK_INT: Int = 0
        }
    }

    open class VERSION_CODES {
        companion object {
            const val LOLLIPOP = 21
            const val M = 23
            const val N = 24
            const val O = 26
            const val P = 28
            const val Q = 29
            const val R = 30
            const val S = 31
            const val TIRAMISU = 33
            const val UPSIDE_DOWN_CAKE = 34
            const val VANILLA_ICE_CREAM = 35
        }
    }

    companion object {
        @JvmField val MANUFACTURER: String = "CloudStream"
        @JvmField val BRAND: String = "CloudStream"
        @JvmField val MODEL: String = "Desktop"
        @JvmField val DEVICE: String = "desktop"
        @JvmField val PRODUCT: String = "desktop"
        @JvmField val HARDWARE: String = "desktop"
        @JvmField val BOARD: String = "desktop"
        @JvmField val HOST: String = "desktop"
        @JvmField val ID: String = "desktop"
        @JvmField val TYPE: String = "user"
        @JvmField val TAGS: String = "release-keys"
        @JvmField val FINGERPRINT: String = "CloudStream/desktop/desktop:15/desktop:user/release-keys"
        @JvmField val SUPPORTED_ABIS: Array<String> = arrayOf("x86_64")
        @JvmField val UNKNOWN: String = "unknown"

        @JvmStatic
        fun getSerial(): String = UNKNOWN
    }
}

class SystemClock private constructor() {
    companion object {
        private val start = System.nanoTime()

        /** Android counts these from boot, desktop from when the app started */
        @JvmStatic fun uptimeMillis(): Long = (System.nanoTime() - start) / 1_000_000
        @JvmStatic fun elapsedRealtime(): Long = uptimeMillis()
        @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - start
        @JvmStatic fun currentThreadTimeMillis(): Long = uptimeMillis()
        @JvmStatic fun sleep(ms: Long) = try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
