package com.lagradost.cloudstream4

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import coil3.compose.setSingletonImageLoaderFactory
import com.lagradost.cloudstream4.browse.desktopImageLoader
import com.lagradost.cloudstream4.compose.LocalFocusOutlineDefault
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.app_name
import com.lagradost.cloudstream4.generated.resources.default_icon
import com.lagradost.cloudstream4.plugins.DesktopPluginManager
import com.lagradost.cloudstream4.settings.desktopPrimaryColor
import com.lagradost.cloudstream4.settings.desktopThemeMode
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun main() {
    DesktopLog.install(AppDirs.logs)
    // Plugins load in the background so a slow or broken plugin never delays the window
    @OptIn(DelicateCoroutinesApi::class)
    GlobalScope.launch(Dispatchers.IO) { DesktopPluginManager.instance.loadAll() }
    application {
        setSingletonImageLoaderFactory(::desktopImageLoader)
        val savedWindow = remember { SavedWindowState(desktopPreferences) }
        val windowState = rememberSavedWindowState(savedWindow)
        val navigator = remember { AppNavigator() }
        Window(
            onCloseRequest = {
                savedWindow.save(windowState)
                exitApplication()
            },
            state = windowState,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.default_icon),
            onPreviewKeyEvent = navigator::onKeyEvent,
        ) {
            MainContent(navigator)
        }
    }
}

@Composable
private fun MainContent(navigator: AppNavigator) {
    val ui = rememberAppSettings().ui
    // Changing the theme or color in settings applies right away
    val theme by ui.theme.changes().collectAsState(ui.theme.get())
    val primaryColor by ui.primaryColor.changes().collectAsState(ui.primaryColor.get())

    CloudStreamTheme(mode = desktopThemeMode(theme), primaryColor = desktopPrimaryColor(primaryColor)) {
        CompositionLocalProvider(LocalFocusOutlineDefault provides false) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                AppShell(navigator)
            }
        }
    }
}
