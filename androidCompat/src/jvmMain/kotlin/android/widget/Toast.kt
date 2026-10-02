package android.widget

import android.content.Context
import com.lagradost.cloudstream4.android.DesktopAndroid

/** Android's Toast, shown as a message at the bottom of the app window */
open class Toast(private val context: Context?) {
    private var text: CharSequence? = null
    private var length = LENGTH_SHORT

    open fun show() {
        text?.toString()?.takeIf { it.isNotBlank() }?.let(DesktopAndroid::showToast)
    }

    open fun cancel() {}

    open fun setText(s: CharSequence?) {
        text = s
    }

    open fun setText(resId: Int) {
        text = context?.getString(resId)
    }

    open fun getDuration(): Int = length

    open fun setDuration(duration: Int) {
        length = duration
    }

    open fun setGravity(gravity: Int, xOffset: Int, yOffset: Int) {}

    open fun setMargin(horizontalMargin: Float, verticalMargin: Float) {}

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast =
            Toast(context).apply {
                setText(text)
                setDuration(duration)
            }

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast =
            Toast(context).apply {
                setText(resId)
                setDuration(duration)
            }
    }
}
