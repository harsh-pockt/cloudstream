package com.lagradost.cloudstream4

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the desktop app keeps its files.
 *
 * Windows: %APPDATA%\CloudStream, macOS: ~/Library/Application Support/CloudStream,
 * Linux: $XDG_CONFIG_HOME/CloudStream or ~/.config/CloudStream.
 *
 * Set the system property `cloudstream.home` to use another folder, for example a portable install.
 */
object AppDirs {
    private const val APP_NAME = "CloudStream"

    val root: Path by lazy {
        System.getProperty("cloudstream.home")?.takeIf { it.isNotBlank() }?.let { return@lazy Paths.get(it) }

        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", APP_NAME)
            os.contains("mac") -> Paths.get(home, "Library", "Application Support", APP_NAME)
            else -> Paths.get(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", APP_NAME)
        }
    }

    val settingsFile: Path get() = root.resolve("settings.json")
    val logs: Path get() = root.resolve("logs")
}
