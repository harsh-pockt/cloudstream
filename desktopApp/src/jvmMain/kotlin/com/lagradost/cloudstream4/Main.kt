package com.lagradost.cloudstream4

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.LocalFocusOutlineDefault
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.app_name
import com.lagradost.cloudstream4.generated.resources.default_icon
import com.lagradost.cloudstream4.generated.resources.preview
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import com.mihon.common.preference.toggle
import com.mihon.presentation.settings.widget.SwitchPreferenceWidget
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun main() {
    DesktopLog.install(AppDirs.logs)
    application {
        val savedWindow = remember { SavedWindowState(desktopPreferences) }
        val windowState = rememberSavedWindowState(savedWindow)
        Window(
            onCloseRequest = {
                savedWindow.save(windowState)
                exitApplication()
            },
            state = windowState,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.default_icon)
        ) {
            MainContent()
        }
    }
}

@Composable
private fun MainContent() {
    val startPaused = rememberAppSettings().player.startPaused
    CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
        CompositionLocalProvider(LocalFocusOutlineDefault provides false) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column {
                    Text("Hello, World!")
                    Row {
                        WhiteButton("Hello in White") {
                        }
                        BlackButton("Hello in Black") {
                        }
                    }
                    // Placeholder until the settings screens are connected, this one is saved to disk
                    val checked by startPaused.changes().collectAsState(startPaused.get())
                    SwitchPreferenceWidget(
                        title = "Start videos paused", subtitle = "Saved between restarts", icon = painterResource(
                            Res.drawable.preview
                        ),
                        checked = checked,
                        onCheckedChanged = { startPaused.toggle() }
                    )
                }
            }
        }
    }
}
