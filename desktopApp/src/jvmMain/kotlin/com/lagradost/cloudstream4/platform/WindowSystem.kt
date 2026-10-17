package com.lagradost.cloudstream4.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.lagradost.cloudstream4.platform.windows.WindowsWindowSystem
import java.awt.Window

/**
 * What the app asks of the system's windows beyond what Compose does. The default here uses only
 * Compose and AWT, so it works on every system. A system can do some of it better its own way,
 * see [WindowsWindowSystem]. The rest of the app only talks to [WindowSystem.current].
 */
interface WindowSystem {
    /** Whether [setFullscreen] is done here, otherwise Compose's own full screen is used */
    val hasFullscreen: Boolean get() = false

    /** Whether [setHidden] is done here, otherwise the window's `visible` is used */
    val hasHide: Boolean get() = false

    /** Full screen without the title bar and border, while the window stays the same native window */
    fun setFullscreen(window: Window, fullscreen: Boolean) {}

    /** Hides the window from the screen and the taskbar, and shows it again as it was */
    fun setHidden(window: Window, hidden: Boolean) {}

    /**
     * The window's background, and where the system lets the app, its title bar and border, in the
     * theme's colours. The background is also what shows for a moment where the window grows,
     * before the page is drawn there again.
     */
    fun paintFrame(window: Window, background: Color, text: Color) {
        window.background = java.awt.Color(background.toArgb(), false)
    }

    /** Lets another program's window, such as the browser's, come in front of the app's */
    fun allowForeground(processId: Long) {}

    /** Compose and AWT only */
    object Default : WindowSystem

    companion object {
        val current: WindowSystem by lazy { WindowsWindowSystem.load() ?: Default }
    }
}
