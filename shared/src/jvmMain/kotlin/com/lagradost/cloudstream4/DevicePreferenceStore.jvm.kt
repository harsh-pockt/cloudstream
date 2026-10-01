package com.lagradost.cloudstream4

import androidx.compose.runtime.Composable
import com.mihon.common.preference.JvmPreferenceStore
import com.mihon.common.preference.PreferenceStore

/** The single preference file for the desktop app, shared by settings and app state such as window size */
val desktopPreferences: PreferenceStore by lazy { JvmPreferenceStore(AppDirs.settingsFile) }

/** The desktop app has one window, so a shared global for settings is enough */
internal val settings by lazy { AppSettings(desktopPreferences) }

@Composable
actual fun rememberAppSettings(): AppSettings {
    return settings
}
