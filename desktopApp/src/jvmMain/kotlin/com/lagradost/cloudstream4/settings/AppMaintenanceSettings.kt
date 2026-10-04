package com.lagradost.cloudstream4.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.AppData
import com.lagradost.cloudstream4.AppDirs
import com.lagradost.cloudstream4.AppVersion
import com.lagradost.cloudstream4.ElevationDeclinedException
import com.lagradost.cloudstream4.WindowsElevated
import com.lagradost.cloudstream4.desktopPreferences
import com.lagradost.cloudstream4.generated.resources.*
import com.lagradost.cloudstream4.theme.AppShapes
import com.lagradost.cloudstream4.theme.BlackButton
import com.lagradost.cloudstream4.theme.WhiteButton
import com.lagradost.cloudstream4.update.AppRelease
import com.lagradost.cloudstream4.update.AppUpdater
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import java.nio.file.Path
import kotlin.system.exitProcess

/** Looks for a newer app version when the app starts */
val checkAppUpdates = desktopPreferences.getBoolean("desktop_check_app_updates", true)

private val updater = AppUpdater()

/**
 * Starts the app again. Installed, the executable is started before this one quits; the new one
 * waits for files this one holds when it erases data. Run from a build, the app only quits.
 */
fun restartApp(): Nothing {
    System.getProperty("jpackage.app-path")?.let { exe -> runCatching { ProcessBuilder(exe).start() } }
    exitProcess(0)
}

/** Downloading and installing a release, shared by the Updates screen and the prompt at startup */
private class UpdateState {
    var progress by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun install(release: AppRelease) {
        error = null
        progress = 0f
        try {
            val installer = updater.download(release, Path.of(System.getProperty("java.io.tmpdir"), "CloudStream-update")) { progress = it }
            // Windows asks for permission while the app is still in front, where its prompt is seen
            withContext(Dispatchers.IO) { updater.startInstaller(installer, start = WindowsElevated::start) }
            // The installer replaces the app's files, which it can only do once the app has quit
            exitProcess(0)
        } catch (_: ElevationDeclinedException) {
            error = "Not updated: Windows needs your permission to install the update"
            progress = null
        } catch (t: Throwable) {
            error = "Could not update: ${t.message ?: t}"
            progress = null
        }
    }
}

@Composable
private fun UpdateActions(release: AppRelease, state: UpdateState, onLater: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.progress?.let {
            Text("Downloading the installer, CloudStream closes and updates when it is done", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.progress == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WhiteButton(onClick = { scope.launch { state.install(release) } }) { Text("Update now") }
            BlackButton(onClick = { runCatching { uriHandler.openUri(release.pageUrl) } }) { Text("What's new") }
            onLater?.let { TextButton(onClick = it) { Text("Later") } }
        }
    }
}

/** Asks to update when a newer version is out, once per start, if the user has not turned the check off */
@Composable
fun AppUpdatePrompt() {
    var release by remember { mutableStateOf<AppRelease?>(null) }
    val state = remember { UpdateState() }
    LaunchedEffect(Unit) {
        if (!checkAppUpdates.get()) return@LaunchedEffect
        release = runCatching { updater.findUpdate() }
            .onFailure { println("WARNING AppUpdatePrompt: Could not check for an update: $it") }
            .getOrNull()
    }
    val current = release ?: return
    AlertDialog(
        // dialog__window_background.xml
        containerColor = MaterialTheme.colorScheme.background,
        shape = AppShapes.dialog,
        onDismissRequest = { if (state.progress == null) release = null },
        title = { Text("CloudStream ${current.version} is available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You have version ${AppVersion.current}.")
                UpdateActions(current, state, onLater = { release = null })
            }
        },
        confirmButton = {},
    )
}

object SettingsUpdatesScreen : SearchableSettings {
    private sealed interface Check {
        data object NotChecked : Check
        data object Checking : Check
        data class Done(val release: AppRelease?, val error: String? = null) : Check
    }

    private var check by mutableStateOf<Check>(Check.NotChecked)
    private val state = UpdateState()

    @Composable
    override fun getTitleRes(): String = "Updates"

    @Composable
    override fun getPreferences(): List<Preference> {
        val scope = rememberCoroutineScope()
        fun checkNow() {
            check = Check.Checking
            scope.launch {
                check = runCatching { Check.Done(updater.findUpdate()) }
                    .getOrElse { Check.Done(null, "Could not check: ${it.message ?: it}") }
            }
        }
        return listOf(
            Preference.PreferenceGroup(
                title = "CloudStream",
                preferenceItems = listOf(
                    Preference.PreferenceItem.CustomPreference("Version") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val current = check
                                Text(
                                    "Version ${AppVersion.current}" + when {
                                        current is Check.Checking -> " · checking for updates"
                                        current is Check.Done && current.error != null -> " · ${current.error}"
                                        current is Check.Done && current.release == null -> " · up to date"
                                        current is Check.Done -> " · version ${current.release!!.version} is available"
                                        else -> ""
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if ((check as? Check.Done)?.release == null) {
                                    BlackButton(enabled = check != Check.Checking, onClick = ::checkNow) { Text("Check now") }
                                }
                            }
                            (check as? Check.Done)?.release?.let { UpdateActions(it, state) }
                        }
                    },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = checkAppUpdates,
                        title = "Check for updates when the app starts",
                        subtitle = "New versions come from github.com/${AppUpdater.REPOSITORY}",
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Extensions",
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = autoUpdateExtensions,
                        title = "Update extensions automatically",
                        subtitle = "Installs new versions of your extensions when the app starts",
                    ),
                ),
            ),
        )
    }
}

/** Clearing the cache, removing extensions or erasing everything, each confirmed and done on a restart */
object SettingsStorageScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = "Storage"

    @Composable
    override fun getPreferences(): List<Preference> {
        var confirm by remember { mutableStateOf<AppData.Reset?>(null) }

        confirm?.let { reset ->
            AlertDialog(
                // dialog__window_background.xml
                containerColor = MaterialTheme.colorScheme.background,
                shape = AppShapes.dialog,
                onDismissRequest = { confirm = null },
                title = { Text("${reset.title}?") },
                text = {
                    Text(
                        when (reset) {
                            AppData.Reset.CACHE -> "Posters and other downloaded files are deleted and downloaded again when needed."
                            AppData.Reset.EXTENSIONS -> "Every installed extension and its files are removed. Your repositories, settings and library stay."
                            AppData.Reset.EVERYTHING -> "Settings, repositories, extensions and everything else are deleted, as if CloudStream had just been installed."
                        } + " CloudStream restarts to do this."
                    )
                },
                confirmButton = {
                    WhiteButton(onClick = {
                        AppData.instance.scheduleReset(reset)
                        restartApp()
                    }) { Text(reset.title) }
                },
                dismissButton = { BlackButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }

        fun item(reset: AppData.Reset, subtitle: String) = Preference.PreferenceItem.TextPreference(
            title = reset.title,
            subtitle = subtitle,
            onClick = { confirm = reset },
        )

        return listOf(
            Preference.PreferenceGroup(
                title = "Data",
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference("CloudStream keeps its data in ${AppDirs.root} and its cache in ${AppDirs.cache}."),
                    item(AppData.Reset.CACHE, "Posters and other files that can be downloaded again"),
                    item(AppData.Reset.EXTENSIONS, "Installed extensions and their files. Repositories, settings and the library stay"),
                    item(AppData.Reset.EVERYTHING, "Start fresh, as if CloudStream had just been installed"),
                ),
            ),
        )
    }
}

/** The Updates and Storage entries on the settings home */
@Composable
fun maintenanceCategories(open: (SearchableSettings) -> Unit): List<Preference.PreferenceItem.TextPreference> = listOf(
    Preference.PreferenceItem.TextPreference(
        title = "Updates",
        subtitle = "Version ${AppVersion.current}",
        icon = painterResource(Res.drawable.ic_baseline_system_update_24),
        onClick = { open(SettingsUpdatesScreen) },
    ),
    Preference.PreferenceItem.TextPreference(
        title = "Storage",
        subtitle = "Clear the cache, remove extensions or start fresh",
        icon = painterResource(Res.drawable.ic_baseline_storage_24),
        onClick = { open(SettingsStorageScreen) },
    ),
)
