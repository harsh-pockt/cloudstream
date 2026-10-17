package com.lagradost.cloudstream4.platform.windows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.lagradost.cloudstream4.platform.WindowSystem
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** The user32 functions needed here, see winuser.h */
@Suppress("FunctionName")
private interface User32 : StdCallLibrary {
    fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
    fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
    fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
    fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
    fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
    fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
    fun IsZoomed(hwnd: Pointer): Boolean
    fun SetForegroundWindow(hwnd: Pointer): Boolean
    fun AllowSetForegroundWindow(processId: Int): Boolean
}

/** The dwmapi function the title bar needs, see dwmapi.h */
@Suppress("FunctionName")
private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

@Structure.FieldOrder("left", "top", "right", "bottom")
internal class Rect : Structure() {
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
}

@Structure.FieldOrder("cbSize", "monitor", "work", "flags")
internal class MonitorInfo : Structure() {
    @JvmField var cbSize = size()
    @JvmField var monitor = Rect()
    @JvmField var work = Rect()
    @JvmField var flags = 0
}

/**
 * The window done the Windows way, through user32 and dwmapi. Only made on Windows, see [load];
 * every other system uses [WindowSystem.Default].
 */
class WindowsWindowSystem private constructor(private val user32: User32, private val dwmapi: DwmApi?) : WindowSystem {
    override val hasFullscreen get() = true
    override val hasHide get() = true

    private class Saved(val style: Long, val rect: Rect, val maximized: Boolean)

    private var saved: Saved? = null
    private var hidden = false

    /**
     * Full screen the way browsers and video players do it on Windows: the window loses its title bar
     * and border and covers the whole monitor, taskbar included. Compose's own full screen keeps the
     * title bar of a decorated window. The window stays the same native window throughout, so the
     * video playing in it carries on.
     */
    override fun setFullscreen(window: Window, fullscreen: Boolean) {
        if (fullscreen) enterFullscreen(window) else exitFullscreen(window)
    }

    private fun enterFullscreen(window: Window) {
        if (saved != null) return
        val hwnd = Native.getWindowPointer(window) ?: return
        // A maximized window is restored first: Windows keeps its maximized size otherwise, and on the
        // way back the content would stay the size of the whole screen, under the taskbar
        val maximized = user32.IsZoomed(hwnd)
        if (maximized) user32.ShowWindow(hwnd, SW_RESTORE)
        val rect = Rect().also { user32.GetWindowRect(hwnd, it) }
        val style = user32.GetWindowLongPtrW(hwnd, GWL_STYLE)
        val info = MonitorInfo()
        val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return
        if (!user32.GetMonitorInfoW(monitor, info)) return
        saved = Saved(style, rect, maximized)
        user32.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
        val m = info.monitor
        user32.SetWindowPos(hwnd, null, m.left, m.top, m.right - m.left, m.bottom - m.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
    }

    private fun exitFullscreen(window: Window) {
        val before = saved ?: return
        saved = null
        val hwnd = Native.getWindowPointer(window) ?: return
        user32.SetWindowLongPtrW(hwnd, GWL_STYLE, before.style)
        val r = before.rect
        user32.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        // Maximizing again lets Windows size it to the screen without the taskbar, as before
        if (before.maximized) user32.ShowWindow(hwnd, SW_MAXIMIZE)
    }

    /**
     * Gone from the screen, the taskbar and Alt+Tab, and back as it was. Compose's own `visible`
     * brings a maximized window back the size of the whole screen, with its bottom under the taskbar.
     */
    override fun setHidden(window: Window, hidden: Boolean) {
        if (hidden == this.hidden) return
        val hwnd = Native.getWindowPointer(window) ?: return
        this.hidden = hidden
        if (hidden) {
            user32.ShowWindow(hwnd, SW_HIDE)
        } else {
            user32.ShowWindow(hwnd, SW_SHOW)
            user32.SetForegroundWindow(hwnd)
        }
    }

    /**
     * The title bar and border are drawn by Windows, white unless told otherwise. Windows 11 takes the
     * exact colours, Windows 10 only switches the title bar to dark for a dark theme.
     */
    override fun paintFrame(window: Window, background: Color, text: Color) {
        super.paintFrame(window, background, text)
        val dwm = dwmapi ?: return
        val hwnd = Native.getWindowPointer(window) ?: return
        fun set(attribute: Int, value: Int) = dwm.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)
        set(DWMWA_USE_IMMERSIVE_DARK_MODE, if (background.luminance() < 0.5f) 1 else 0)
        set(DWMWA_CAPTION_COLOR, background.colorRef())
        set(DWMWA_BORDER_COLOR, background.colorRef())
        set(DWMWA_TEXT_COLOR, text.colorRef())
    }

    /** Windows otherwise refuses another program's window coming in front of the app's */
    override fun allowForeground(processId: Long) {
        user32.AllowSetForegroundWindow(processId.toInt())
    }

    /** A COLORREF, 0x00BBGGRR */
    private fun Color.colorRef(): Int {
        val argb = toArgb()
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    companion object {
        private const val GWL_STYLE = -16
        private const val WS_CAPTION = 0x00C00000L
        private const val WS_THICKFRAME = 0x00040000L
        private const val MONITOR_DEFAULTTONEAREST = 2
        private const val SWP_NOZORDER = 0x0004
        private const val SWP_NOACTIVATE = 0x0010
        private const val SWP_FRAMECHANGED = 0x0020
        private const val SW_HIDE = 0
        private const val SW_SHOW = 5
        private const val SW_MAXIMIZE = 3
        private const val SW_RESTORE = 9
        private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
        private const val DWMWA_BORDER_COLOR = 34
        private const val DWMWA_CAPTION_COLOR = 35
        private const val DWMWA_TEXT_COLOR = 36

        /** Null when not on Windows, or when user32 could not be loaded */
        fun load(): WindowsWindowSystem? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
            val user32 = runCatching { Native.load("user32", User32::class.java) }
                .onFailure { println("ERROR WindowsWindowSystem: user32 could not be loaded: $it") }
                .getOrNull() ?: return null
            val dwmapi = runCatching { Native.load("dwmapi", DwmApi::class.java) }
                .onFailure { println("ERROR WindowsWindowSystem: dwmapi could not be loaded: $it") }
                .getOrNull()
            return WindowsWindowSystem(user32, dwmapi)
        }
    }
}
