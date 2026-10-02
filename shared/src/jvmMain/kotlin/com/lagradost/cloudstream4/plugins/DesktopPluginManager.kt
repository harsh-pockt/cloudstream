package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream4.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

data class InstalledPlugin(
    val internalName: String,
    val name: String,
    val version: Int,
    val repositoryUrl: String,
)

/**
 * Installs plugin jars into one folder, keeps a list of them in installed.json and loads them.
 * All changes go through one lock, so an install and an uninstall can never interleave.
 */
class DesktopPluginManager(
    private val dir: Path,
    private val client: RepositoryClient = RepositoryClient(),
) {
    private val indexFile = dir.resolve("installed.json")
    private val lock = Mutex()
    private val loaded = mutableMapOf<String, LoadedPlugin>()

    private val _installed = MutableStateFlow(readIndex())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    /** Why a plugin failed to load, by internal name */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** Internal names of the plugins that are loaded, changes whenever providers are added or removed */
    private val _loadedNames = MutableStateFlow<Set<String>>(emptySet())
    val loadedNames: StateFlow<Set<String>> = _loadedNames.asStateFlow()

    fun loadedPlugin(internalName: String): LoadedPlugin? = loaded[internalName]

    private fun publishLoaded() {
        _loadedNames.value = loaded.keys.toSet()
    }

    private fun jarFile(internalName: String): Path = dir.resolve("$internalName.jar")

    /** Loads every installed plugin, called once at startup. A plugin that fails is skipped and its error kept */
    suspend fun loadAll() = lock.withLock {
        withContext(Dispatchers.IO) {
            for (plugin in _installed.value) {
                if (loaded.containsKey(plugin.internalName)) continue
                loadLocked(plugin.internalName)
            }
        }
    }

    /** Downloads, verifies and loads a plugin, replacing an older version that is already installed */
    suspend fun install(repositoryUrl: String, plugin: RepoPlugin) {
        val bytes = client.downloadJar(plugin)
        lock.withLock {
            withContext(Dispatchers.IO) {
                dir.createDirectories()
                loaded.remove(plugin.internalName)?.unload()
                publishLoaded()
                val target = jarFile(plugin.internalName)
                val tmp = dir.resolve("${plugin.internalName}.jar.tmp")
                tmp.writeBytes(bytes)
                Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)

                val entry = InstalledPlugin(plugin.internalName, plugin.name, plugin.version, repositoryUrl)
                writeIndex(_installed.value.filterNot { it.internalName == plugin.internalName } + entry)
                loadLocked(plugin.internalName)?.let { throw it }
            }
        }
    }

    suspend fun uninstall(internalName: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            loaded.remove(internalName)?.unload()
            publishLoaded()
            jarFile(internalName).deleteIfExists()
            writeIndex(_installed.value.filterNot { it.internalName == internalName })
            _errors.update { it - internalName }
        }
    }

    /** Returns the error if the plugin could not be loaded */
    private fun loadLocked(internalName: String): Throwable? {
        val file = jarFile(internalName)
        return try {
            if (!file.exists()) throw PluginDownloadException("$file is missing, install the plugin again")
            loaded[internalName] = PluginLoader.load(file)
            publishLoaded()
            _errors.update { it - internalName }
            println("INFO DesktopPluginManager: Loaded $internalName")
            null
        } catch (t: Throwable) {
            println("ERROR DesktopPluginManager: Could not load $internalName: $t")
            t.printStackTrace()
            _errors.update { it + (internalName to (t.message ?: t.toString())) }
            t
        }
    }

    private fun readIndex(): List<InstalledPlugin> = runCatching {
        if (!indexFile.exists()) return emptyList()
        Json.parseToJsonElement(indexFile.readText()).jsonArray.map { element ->
            val obj = element.jsonObject
            InstalledPlugin(
                internalName = obj["internalName"]!!.jsonPrimitive.content,
                name = obj["name"]!!.jsonPrimitive.content,
                version = obj["version"]!!.jsonPrimitive.int,
                repositoryUrl = obj["repositoryUrl"]!!.jsonPrimitive.content,
            )
        }
    }.getOrElse {
        println("ERROR DesktopPluginManager: Could not read $indexFile: $it")
        emptyList()
    }

    private fun writeIndex(plugins: List<InstalledPlugin>) {
        dir.createDirectories()
        val json = JsonArray(plugins.map {
            JsonObject(
                mapOf(
                    "internalName" to JsonPrimitive(it.internalName),
                    "name" to JsonPrimitive(it.name),
                    "version" to JsonPrimitive(it.version),
                    "repositoryUrl" to JsonPrimitive(it.repositoryUrl),
                )
            )
        })
        val tmp = dir.resolve("installed.json.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp, indexFile, REPLACE_EXISTING, ATOMIC_MOVE)
        _installed.value = plugins
    }

    companion object {
        /** The app's plugin manager, keeping plugins in the plugins folder next to the settings */
        val instance: DesktopPluginManager by lazy { DesktopPluginManager(AppDirs.root.resolve("plugins")) }
    }
}
