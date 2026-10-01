package com.lagradost.cloudstream4

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.generated.resources.*
import com.lagradost.cloudstream4.settings.SettingsHomeScreen
import com.mihon.presentation.LocalBackPress
import com.mihon.presentation.settings.SearchableSettings
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

enum class Destination(
    val title: StringResource,
    val icon: DrawableResource,
    val selectedIcon: DrawableResource,
) {
    Home(Res.string.title_home, Res.drawable.home_icon_outline_24, Res.drawable.home_icon_filled_24),
    Search(Res.string.title_search, Res.drawable.search_icon, Res.drawable.search_icon),
    Library(Res.string.library, Res.drawable.library_icon, Res.drawable.library_icon_filled),
    Settings(Res.string.title_settings, Res.drawable.settings_icon_outline, Res.drawable.settings_icon_filled),
}

/**
 * Navigation for the desktop app: a rail on the left and the selected destination on the right.
 * Ctrl+1 to Ctrl+4 switch destination, Escape or Alt+Left goes back.
 */
class AppNavigator {
    var destination by mutableStateOf(Destination.Home)
        private set

    /** Settings pages opened on top of the settings home, last is shown */
    val settingsStack = mutableStateListOf<SearchableSettings>()

    fun select(destination: Destination) {
        // Selecting Settings again returns to the settings home
        if (destination == Destination.Settings && this.destination == Destination.Settings) settingsStack.clear()
        this.destination = destination
    }

    fun open(page: SearchableSettings) {
        settingsStack.add(page)
    }

    /** Returns false when there is nothing to go back to */
    fun back(): Boolean {
        if (destination == Destination.Settings && settingsStack.isNotEmpty()) {
            settingsStack.removeAt(settingsStack.lastIndex)
            return true
        }
        return false
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed) {
            val index = listOf(Key.One, Key.Two, Key.Three, Key.Four).indexOf(event.key)
            if (index >= 0) {
                select(Destination.entries[index])
                return true
            }
        }
        if (event.key == Key.Escape || (event.isAltPressed && event.key == Key.DirectionLeft)) return back()
        return false
    }
}

@Composable
fun AppShell(navigator: AppNavigator) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Destination.entries.forEach { destination ->
                    val selected = navigator.destination == destination
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navigator.select(destination) },
                        icon = {
                            Icon(
                                painterResource(if (selected) destination.selectedIcon else destination.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.title)) },
                    )
                }
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (navigator.destination) {
                Destination.Settings -> {
                    val page = navigator.settingsStack.lastOrNull()
                        ?: remember(navigator) { SettingsHomeScreen(navigator::open) }
                    val back: (() -> Unit)? = if (navigator.settingsStack.isEmpty()) null else ({ navigator.back() })
                    CompositionLocalProvider(LocalBackPress provides back) {
                        page.Content()
                    }
                }

                else -> ComingSoon(navigator.destination)
            }
        }
    }
}

/** Placeholder for the destinations built in later phases */
@Composable
private fun ComingSoon(destination: Destination) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(destination.title), style = MaterialTheme.typography.headlineMedium)
        Text(
            "Not built yet on desktop",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
