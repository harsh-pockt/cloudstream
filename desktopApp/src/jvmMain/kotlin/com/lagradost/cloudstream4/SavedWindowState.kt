package com.lagradost.cloudstream4

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.mihon.common.preference.PreferenceData.Companion.appStateKey
import com.mihon.common.preference.PreferenceStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** Window size, position and maximized state, kept as app state so it is left out of backups */
class SavedWindowState(preferences: PreferenceStore) {
    private val x = preferences.getFloat(appStateKey("window_x"), Float.NaN)
    private val y = preferences.getFloat(appStateKey("window_y"), Float.NaN)
    private val width = preferences.getFloat(appStateKey("window_width"), 1280f)
    private val height = preferences.getFloat(appStateKey("window_height"), 800f)
    private val maximized = preferences.getBoolean(appStateKey("window_maximized"), false)

    fun toWindowState(): WindowState {
        val size = DpSize(width.get().coerceAtLeast(MIN_SIZE).dp, height.get().coerceAtLeast(MIN_SIZE).dp)
        return WindowState(
            placement = if (maximized.get()) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = savedPosition(size) ?: WindowPosition.PlatformDefault,
            size = size,
        )
    }

    fun save(state: WindowState) {
        maximized.set(state.placement == WindowPlacement.Maximized)
        // Only remember the floating size, otherwise un-maximizing would keep the window full screen
        if (state.placement != WindowPlacement.Floating) return
        width.set(state.size.width.value)
        height.set(state.size.height.value)
        val position = state.position
        if (position is WindowPosition.Absolute) {
            x.set(position.x.value)
            y.set(position.y.value)
        }
    }

    /** The saved position, unless the window would open on a monitor that is no longer connected */
    private fun savedPosition(size: DpSize): WindowPosition? {
        val x = x.get()
        val y = y.get()
        if (x.isNaN() || y.isNaN()) return null
        val titleBar = Rectangle(x.toInt(), y.toInt(), size.width.value.toInt(), 40)
        val visible = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .any { it.defaultConfiguration.bounds.intersects(titleBar) }
        }.getOrDefault(false)
        return if (visible) WindowPosition(x.dp, y.dp) else null
    }

    private companion object {
        const val MIN_SIZE = 400f
    }
}

/** A WindowState that starts where the window was last closed and saves every change */
@OptIn(FlowPreview::class)
@Composable
fun rememberSavedWindowState(saved: SavedWindowState): WindowState {
    val state = remember { saved.toWindowState() }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.placement, state.size, state.position) }
            .debounce(500)
            .collect { saved.save(state) }
    }
    return state
}
