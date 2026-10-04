package com.lagradost.cloudstream4

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.KeyboardFocusManager

/** The shell32 function that starts a program as administrator, see shellapi.h */
@Suppress("FunctionName")
private interface Shell32 : StdCallLibrary {
    fun ShellExecuteExW(info: ShellExecuteInfo): Boolean
}

@Structure.FieldOrder(
    "cbSize", "fMask", "hwnd", "lpVerb", "lpFile", "lpParameters", "lpDirectory", "nShow",
    "hInstApp", "lpIDList", "lpClass", "hkeyClass", "dwHotKey", "hIcon", "hProcess",
)
internal class ShellExecuteInfo : Structure() {
    @JvmField var cbSize = 0
    @JvmField var fMask = 0
    @JvmField var hwnd: Pointer? = null
    @JvmField var lpVerb: WString? = null
    @JvmField var lpFile: WString? = null
    @JvmField var lpParameters: WString? = null
    @JvmField var lpDirectory: WString? = null
    @JvmField var nShow = 0
    @JvmField var hInstApp: Pointer? = null
    @JvmField var lpIDList: Pointer? = null
    @JvmField var lpClass: WString? = null
    @JvmField var hkeyClass: Pointer? = null
    @JvmField var dwHotKey = 0
    @JvmField var hIcon: Pointer? = null
    @JvmField var hProcess: Pointer? = null
}

private val shell32: Shell32? by lazy {
    if (!System.getProperty("os.name").startsWith("Windows")) return@lazy null
    runCatching { Native.load("shell32", Shell32::class.java) }
        .onFailure { println("ERROR Shell32: shell32 could not be loaded: $it") }
        .getOrNull()
}

class ElevationDeclinedException : Exception("Windows was not given permission to install the update")

/**
 * Starts a program as administrator. Windows asks the user first, and shows that prompt in front
 * only when the program asking is in front: one started by the app after it quit asks from the
 * taskbar, unseen, and Windows cancels it two minutes later. So the app asks while it is open, and
 * this returns once the user has answered.
 */
object WindowsElevated {
    private const val SEE_MASK_NOASYNC = 0x100
    // Without it Windows shows its own error box, and then reports any failure as cancelled
    private const val SEE_MASK_FLAG_NO_UI = 0x400
    private const val SW_SHOWNORMAL = 1
    private const val ERROR_CANCELLED = 1223

    /** Starts [command], throwing [ElevationDeclinedException] when the user says no */
    fun start(command: List<String>) {
        val shell32 = shell32 ?: run {
            ProcessBuilder(command).start()
            return
        }
        val info = ShellExecuteInfo().apply {
            cbSize = size()
            fMask = SEE_MASK_NOASYNC or SEE_MASK_FLAG_NO_UI
            hwnd = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.let { Native.getWindowPointer(it) }
            lpVerb = WString("runas")
            lpFile = WString(command.first())
            lpParameters = WString(command.drop(1).joinToString(" ") { if (' ' in it) "\"$it\"" else it })
            nShow = SW_SHOWNORMAL
        }
        if (!shell32.ShellExecuteExW(info)) {
            val error = Native.getLastError()
            if (error == ERROR_CANCELLED) throw ElevationDeclinedException()
            throw IllegalStateException("${command.first()} could not be started, Windows error $error")
        }
    }
}
