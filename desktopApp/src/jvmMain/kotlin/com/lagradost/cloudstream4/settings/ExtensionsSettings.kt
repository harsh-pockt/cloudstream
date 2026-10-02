package com.lagradost.cloudstream4.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktopPreferences
import com.lagradost.cloudstream4.generated.resources.*
import com.lagradost.cloudstream4.plugins.DesktopPluginManager
import com.lagradost.cloudstream4.plugins.RepoPlugin
import com.lagradost.cloudstream4.plugins.Repository
import com.lagradost.cloudstream4.plugins.RepositoryClient
import com.mihon.common.preference.minusAssign
import com.mihon.common.preference.plusAssign
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Repository URLs the user added, kept separate from the Android REPOSITORIES_KEY data store */
private val repositories = desktopPreferences.getStringSet("desktop_repositories", emptySet())

private val client = RepositoryClient()

/** Adds every repository in the community list, which is what MegaRepo does on Android. Returns how many were new */
private suspend fun addCommunityRepositories(): Int {
    val community = client.fetchCommunityRepositories()
    val new = community.filterNot { it in repositories.get() }
    repositories.set(repositories.get() + new)
    return new.size
}

/**
 * Adds what the user typed: a repository URL, a cloudstreamrepo:// link or a short code such as
 * "megarepo". MegaRepo itself is not added: its only plugin is an Android helper that adds the
 * community repositories, so they are added directly instead. Returns a message for the user.
 */
private suspend fun addRepository(input: String): String {
    val url = client.resolveRepositoryUrl(input) ?: return "No repository found for \"${input.trim()}\""
    val plugins = client.fetchPlugins(client.fetchRepository(url))
    if (plugins.isNotEmpty() && plugins.all { it.internalName == RepositoryClient.MEGA_REPO_PLUGIN }) {
        val added = addCommunityRepositories()
        return "MegaRepo: added $added community repositories" + if (added == 0) ", you already had them all" else ""
    }
    repositories += url
    return "Added ${url.removePrefix("https://")}"
}

/** Repositories and installed extensions. Only plugins built with `isCrossPlatform = true` can run on desktop */
class ExtensionsScreen(private val open: (SearchableSettings) -> Unit) : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = "Extensions"

    @Composable
    override fun getPreferences(): List<Preference> {
        val manager = DesktopPluginManager.instance
        val repos by repositories.collectAsState()
        val installed by manager.installed.collectAsState()
        val errors by manager.errors.collectAsState()
        val scope = rememberCoroutineScope()
        val busy = remember { mutableStateMapOf<String, Boolean>() }

        val repoItems = repos.sorted().map { url ->
            Preference.PreferenceItem.TextPreference(
                title = url.removePrefix("https://").removePrefix("raw.githubusercontent.com/"),
                subtitle = if (url == RepositoryClient.OFFICIAL_REPOSITORY) "Official repository" else url,
                icon = painterResource(Res.drawable.extension_24px),
                onClick = { open(RepositoryScreen(url)) },
                widget = { TextButton(onClick = { repositories -= url }) { Text("Remove") } },
            )
        }

        val addRepository = Preference.PreferenceItem.CustomPreference(title = "Add repository") {
            var text by remember { mutableStateOf("") }
            var adding by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf<String?>(null) }
            fun run(action: suspend () -> String) {
                adding = true
                message = null
                scope.launch {
                    message = try {
                        action()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        "Could not add it: ${t.message ?: t}"
                    }
                    adding = false
                }
            }
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Repository URL or short code, for example megarepo") },
                    )
                    BusyOr(adding) {
                        Button(
                            enabled = text.isNotBlank(),
                            onClick = {
                                val input = text
                                run { addRepository(input).also { text = "" } }
                            },
                        ) { Text("Add") }
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    if (RepositoryClient.OFFICIAL_REPOSITORY !in repos) {
                        TextButton(onClick = { repositories += RepositoryClient.OFFICIAL_REPOSITORY }) {
                            Text("Add the official repository")
                        }
                    }
                    TextButton(enabled = !adding, onClick = {
                        run { "Added ${addCommunityRepositories()} community repositories" }
                    }) { Text("Add all community repositories") }
                }
            }
        }

        val installedItems = installed.sortedBy { it.name.lowercase() }.map { plugin ->
            val error = errors[plugin.internalName]
            Preference.PreferenceItem.TextPreference(
                title = plugin.name,
                subtitle = error?.let { "Failed to load: $it" } ?: "Version ${plugin.version}",
                widget = {
                    BusyOr(busy[plugin.internalName] == true) {
                        OutlinedButton(onClick = {
                            busy[plugin.internalName] = true
                            scope.launch {
                                runCatching { manager.uninstall(plugin.internalName) }
                                busy.remove(plugin.internalName)
                            }
                        }) { Text("Uninstall") }
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceGroup(title = "Repositories", preferenceItems = repoItems + addRepository),
            Preference.PreferenceGroup(
                title = "Installed",
                preferenceItems = installedItems.ifEmpty {
                    listOf(Preference.PreferenceItem.InfoPreference("No extensions installed yet. Open a repository to install some."))
                },
            ),
        )
    }
}

/** The plugins in one repository, with install, update and uninstall */
class RepositoryScreen(private val url: String) : SearchableSettings {
    private sealed interface State {
        data object Loading : State
        data class Failed(val message: String) : State
        data class Loaded(val repository: Repository, val plugins: List<RepoPlugin>) : State
    }

    private var state by mutableStateOf<State>(State.Loading)

    @Composable
    override fun getTitleRes(): String = (state as? State.Loaded)?.repository?.name ?: "Repository"

    @Composable
    override fun getPreferences(): List<Preference> {
        val manager = DesktopPluginManager.instance
        val installed by manager.installed.collectAsState()
        val scope = rememberCoroutineScope()
        val busy = remember { mutableStateMapOf<String, Boolean>() }
        val failures = remember { mutableStateMapOf<String, String>() }

        LaunchedEffect(url) {
            if (state is State.Loaded) return@LaunchedEffect
            state = try {
                val repository = client.fetchRepository(url)
                State.Loaded(repository, client.fetchPlugins(repository))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                State.Failed(t.message ?: t.toString())
            }
        }

        return when (val current = state) {
            State.Loading -> listOf(Preference.PreferenceItem.CustomPreference("Loading") {
                CircularProgressIndicator(Modifier.padding(24.dp))
            })

            is State.Failed -> listOf(Preference.PreferenceItem.InfoPreference("Could not load $url: ${current.message}"))

            is State.Loaded -> if (current.plugins.any { it.internalName == RepositoryClient.MEGA_REPO_PLUGIN }) {
                listOf(megaRepoGroup(scope))
            } else {
                val (desktop, androidOnly) = current.plugins.sortedBy { it.name.lowercase() }.partition { it.supportsDesktop }
                // Product Sans draws "(5)" as a circled digit, so counts use a separator instead of parentheses
                listOfNotNull(
                    Preference.PreferenceGroup(
                        title = "Available on desktop · ${desktop.size}",
                        preferenceItems = if (desktop.isEmpty()) listOf(
                            Preference.PreferenceItem.InfoPreference(
                                "Nothing in this repository runs on desktop yet. All ${androidOnly.size} of its " +
                                        "extensions are Android only, see below."
                            )
                        ) else desktop.map { plugin ->
                            val installedVersion = installed.firstOrNull { it.internalName == plugin.internalName }?.version
                            Preference.PreferenceItem.TextPreference(
                                title = plugin.name,
                                subtitle = failures[plugin.internalName]
                                    ?: listOfNotNull(
                                        plugin.description,
                                        plugin.language?.uppercase(),
                                        "v${plugin.version}",
                                        "Down".takeIf { plugin.status == 0 },
                                    ).joinToString(" · "),
                                widget = {
                                    BusyOr(busy[plugin.internalName] == true) {
                                        fun run(action: suspend () -> Unit) {
                                            busy[plugin.internalName] = true
                                            failures.remove(plugin.internalName)
                                            scope.launch {
                                                runCatching { action() }.onFailure {
                                                    failures[plugin.internalName] = "Failed: ${it.message ?: it}"
                                                }
                                                busy.remove(plugin.internalName)
                                            }
                                        }
                                        when {
                                            installedVersion == null -> Button(onClick = {
                                                run { manager.install(url, plugin) }
                                            }) { Text("Install") }

                                            installedVersion < plugin.version -> Button(onClick = {
                                                run { manager.install(url, plugin) }
                                            }) { Text("Update") }

                                            else -> OutlinedButton(onClick = {
                                                run { manager.uninstall(plugin.internalName) }
                                            }) { Text("Uninstall") }
                                        }
                                    }
                                },
                            )
                        },
                    ),
                    if (androidOnly.isEmpty()) null else Preference.PreferenceGroup(
                        title = "Android only · ${androidOnly.size}",
                        preferenceItems = listOf(
                            Preference.PreferenceItem.InfoPreference(
                                "These extensions have no desktop build yet. Their authors can publish one by setting " +
                                        "isCrossPlatform = true in the extension's build.gradle.kts."
                            )
                        ) + androidOnly.map { plugin ->
                            Preference.PreferenceItem.TextPreference(
                                title = plugin.name,
                                subtitle = plugin.description,
                                enabled = false,
                            )
                        },
                    ),
                )
            }
        }
    }
}

/** MegaRepo holds no extensions, only an Android helper that adds the community repositories */
@Composable
private fun megaRepoGroup(scope: CoroutineScope): Preference.PreferenceGroup {
    var result by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    return Preference.PreferenceGroup(
        title = "MegaRepo",
        preferenceItems = listOf(
            Preference.PreferenceItem.InfoPreference(
                "MegaRepo has no extensions of its own. Its only plugin, MegaProvider, adds all the community " +
                        "repositories on Android. On desktop the app does that for you."
            ),
            Preference.PreferenceItem.CustomPreference("Add all community repositories") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyOr(adding) {
                        Button(onClick = {
                            adding = true
                            scope.launch {
                                result = runCatching { "Added ${addCommunityRepositories()} community repositories" }
                                    .getOrElse { "Could not add them: ${it.message ?: it}" }
                                adding = false
                            }
                        }) { Text("Add all community repositories") }
                    }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
        ),
    )
}

@Composable
private fun BusyOr(busy: Boolean, content: @Composable () -> Unit) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
    } else {
        content()
    }
}
