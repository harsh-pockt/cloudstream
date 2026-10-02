package com.lagradost.cloudstream4

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** The user32 functions full screen needs, see winuser.h */
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
 * Full screen the way browsers and video players do it on Windows: the window loses its title bar
 * and border and covers the whole monitor, taskbar included. Compose's own full screen keeps the
 * title bar of a decorated window. The window stays the same native window throughout, so the
 * video playing in it carries on.
 */
object WindowsFullscreen {
    private const val GWL_STYLE = -16
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SW_MAXIMIZE = 3
    private const val SW_RESTORE = 9

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").startsWith("Windows")) return@lazy null
        runCatching { Native.load("user32", User32::class.java) }
            .onFailure { println("ERROR WindowsFullscreen: user32 could not be loaded: $it") }
            .getOrNull()
    }

    /** Whether this platform has it, otherwise Compose's own full screen is used */
    val supported: Boolean get() = user32 != null

    private class Saved(val style: Long, val rect: Rect, val maximized: Boolean)

    private var saved: Saved? = null

    val active: Boolean get() = saved != null

    fun enter(window: Window) {
        val user32 = user32 ?: return
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

    fun exit(window: Window) {
        val user32 = user32 ?: return
        val before = saved ?: return
        saved = null
        val hwnd = Native.getWindowPointer(window) ?: return
        user32.SetWindowLongPtrW(hwnd, GWL_STYLE, before.style)
        val r = before.rect
        user32.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        // Maximizing again lets Windows size it to the screen without the taskbar, as before
        if (before.maximized) user32.ShowWindow(hwnd, SW_MAXIMIZE)
    }
}
