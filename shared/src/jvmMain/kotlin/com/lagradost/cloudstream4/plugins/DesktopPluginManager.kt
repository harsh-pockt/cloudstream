package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream4.AppDirs
import com.lagradost.cloudstream4.android.DesktopAndroid
import com.lagradost.cloudstream4.android.DexConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.boolean
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
    /** Converted from the Android version, whose .cs3 is kept in the android folder */
    val isAndroid: Boolean = false,
    /** The converter version that made the jar, see CONVERTER_VERSION */
    val convertedWith: Int = 0,
    /** From the repository, with %size% where the size goes like Android */
    val iconUrl: String? = null,
)

/** A newer version of an installed plugin in its repository */
data class PluginUpdate(val installed: InstalledPlugin, val available: RepoPlugin)

/**
 * Installs plugin jars into one folder, keeps a list of them in installed.json and loads them.
 * All changes go through one lock, so an install and an uninstall can never interleave.
 *
 * A plugin only built for Android is downloaded as its .cs3 and converted into a jar. The .cs3 is
 * kept, so a later app version with a better converter converts it again without a download.
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
    private fun androidFile(internalName: String): Path = dir.resolve("android").resolve("$internalName.cs3")

    init {
        // Android plugins look themselves up in the Android app's plugin manager
        PluginManager.installed = {
            _installed.value.map { PluginData(it.internalName, it.repositoryUrl, true, jarFile(it.internalName).toString(), it.version) }
        }
    }

    /** Loads every installed plugin, called once at startup. A plugin that fails is skipped and its error kept */
    suspend fun loadAll() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                for (plugin in _installed.value) {
                    if (loaded.containsKey(plugin.internalName)) continue
                    if (plugin.isAndroid && plugin.convertedWith != CONVERTER_VERSION) reconvertLocked(plugin)
                    loadLocked(plugin.internalName)
                }
            }
        }
        // Android plugins wait for this to register things that need the other plugins
        runCatching { MainActivity.afterPluginsLoadedEvent.invoke(false) }
            .onFailure { println("ERROR DesktopPluginManager: A plugin failed in afterPluginsLoadedEvent: $it") }
    }

    /**
     * Downloads, verifies and loads a plugin, replacing an older version that is already installed.
     * A plugin only built for Android is converted first, which takes a few seconds for large ones.
     */
    suspend fun install(repositoryUrl: String, plugin: RepoPlugin) {
        val android = !plugin.supportsDesktop
        val bytes = if (android) client.downloadAndroidPlugin(plugin) else client.downloadJar(plugin)
        lock.withLock {
            withContext(Dispatchers.IO) {
                dir.createDirectories()
                loaded.remove(plugin.internalName)?.unload()
                publishLoaded()
                val target = jarFile(plugin.internalName)
                if (android) {
                    val cs3 = androidFile(plugin.internalName)
                    cs3.parent.createDirectories()
                    writeAtomically(cs3, bytes)
                    convert(plugin.internalName, cs3, target)
                } else {
                    writeAtomically(target, bytes)
                    androidFile(plugin.internalName).deleteIfExists()
                }

                val entry = InstalledPlugin(
                    plugin.internalName, plugin.name, plugin.version, repositoryUrl,
                    isAndroid = android, convertedWith = if (android) CONVERTER_VERSION else 0,
                    iconUrl = plugin.iconUrl,
                )
                writeIndex(_installed.value.filterNot { it.internalName == plugin.internalName } + entry)
                loadLocked(plugin.internalName)?.let { throw it }
            }
        }
    }

    /** Installed plugins with a newer version in their repository. A repository that fails to load is skipped */
    suspend fun findUpdates(): List<PluginUpdate> = coroutineScope {
        _installed.value.groupBy { it.repositoryUrl }.map { (url, plugins) ->
            async {
                val available = runCatching { client.fetchPlugins(client.fetchRepository(url)) }
                    .onFailure { println("WARNING DesktopPluginManager: Could not check $url for updates: $it") }
                    .getOrNull() ?: return@async emptyList()
                plugins.mapNotNull { plugin ->
                    available.firstOrNull { it.internalName == plugin.internalName && it.version > plugin.version && it.canInstall }
                        ?.let { PluginUpdate(plugin, it) }
                }
            }
        }.awaitAll().flatten()
    }

    /** Installs the updates one after another. Returns why each one that failed did, by internal name */
    suspend fun installUpdates(updates: List<PluginUpdate>): Map<String, String> {
        val failed = mutableMapOf<String, String>()
        for (update in updates) {
            runCatching { install(update.installed.repositoryUrl, update.available) }
                .onFailure { failed[update.installed.internalName] = it.message ?: it.toString() }
        }
        return failed
    }

    suspend fun uninstall(internalName: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            loaded.remove(internalName)?.unload()
            publishLoaded()
            jarFile(internalName).deleteIfExists()
            androidFile(internalName).deleteIfExists()
            writeIndex(_installed.value.filterNot { it.internalName == internalName })
            _errors.update { it - internalName }
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private fun convert(internalName: String, cs3: Path, target: Path) {
        val start = System.nanoTime()
        val result = try {
            DexConverter.convert(cs3, target)
        } catch (e: OutOfMemoryError) {
            throw PluginDownloadException("$internalName is too large to convert with the memory the app has")
        } catch (e: Exception) {
            throw PluginDownloadException("Could not convert $internalName for desktop: ${e.message ?: e}")
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("INFO DesktopPluginManager: Converted $internalName in $millis ms, ${result.failedMethods} methods failed")
    }

    /** Converts a plugin again after the converter changed. Without its .cs3 the old jar is kept */
    private fun reconvertLocked(plugin: InstalledPlugin) {
        val cs3 = androidFile(plugin.internalName)
        if (!cs3.exists()) return
        runCatching { convert(plugin.internalName, cs3, jarFile(plugin.internalName)) }
            .onSuccess {
                writeIndex(_installed.value.map { if (it.internalName == plugin.internalName) it.copy(convertedWith = CONVERTER_VERSION) else it })
            }
            .onFailure { println("ERROR DesktopPluginManager: Could not convert ${plugin.internalName} again: $it") }
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
                isAndroid = obj["isAndroid"]?.jsonPrimitive?.boolean ?: false,
                convertedWith = obj["convertedWith"]?.jsonPrimitive?.int ?: 0,
                iconUrl = obj["iconUrl"]?.jsonPrimitive?.takeIf { it.isString }?.content,
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
                    "isAndroid" to JsonPrimitive(it.isAndroid),
                    "convertedWith" to JsonPrimitive(it.convertedWith),
                    "iconUrl" to JsonPrimitive(it.iconUrl),
                )
            )
        })
        val tmp = dir.resolve("installed.json.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp, indexFile, REPLACE_EXISTING, ATOMIC_MOVE)
        _installed.value = plugins
    }

    companion object {
        /** Raise when the conversion changes, so Android plugins installed before are converted again */
        const val CONVERTER_VERSION = 1

        /** The app's plugin manager, keeping plugins in the plugins folder next to the settings */
        val instance: DesktopPluginManager by lazy {
            DesktopAndroid.init(AppDirs.root.resolve("android"))
            DesktopPluginManager(AppDirs.root.resolve("plugins"))
        }
    }
}
