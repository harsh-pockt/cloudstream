package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream4.android.AndroidPluginClassLoader
import com.lagradost.cloudstream4.android.DesktopAndroid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.jar.JarFile

/** A plugin jar that is loaded and running */
class LoadedPlugin internal constructor(
    val file: Path,
    val instance: BasePlugin,
    private val classLoader: AndroidPluginClassLoader,
) {
    /** Android classes the plugin used that desktop only has empty stubs of, so those parts do nothing */
    val stubbedClasses: Set<String> get() = classLoader.stubbedClasses

    /** The providers this plugin registered when it loaded */
    val providers: List<MainAPI>
        get() = APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it.sourcePlugin == instance.filename } }

    /** Runs the plugin's beforeUnload, removes everything it registered and releases the jar file */
    fun unload() {
        val filename = instance.filename
        runCatching { instance.beforeUnload() }.onFailure {
            println("ERROR PluginLoader: beforeUnload failed for $file: $it")
        }
        APIHolder.apis.filter { it.sourcePlugin == filename }.forEach(APIHolder::removePluginMapping)
        APIHolder.allProviders.removeAll { it.sourcePlugin == filename }
        extractorApis.removeAll { it.sourcePlugin == filename }
        // Closing releases the file lock, which Windows needs before the jar can be replaced or deleted
        classLoader.close()
    }
}

/**
 * Loads a plugin jar: the cross-platform .jar a plugin publishes when it is built with
 * `isCrossPlatform = true`, or one converted from an Android plugin by DexConverter.
 *
 * The cross-platform jar has no manifest.json, so the plugin class is the one annotated with
 * @CloudstreamPlugin. A converted jar keeps the Android plugin's manifest.json, which is used first.
 * Android plugins get load(context) with the desktop activity, like Android calls it with its activity.
 */
object PluginLoader {
    private const val ANNOTATION_DESCRIPTOR = "Lcom/lagradost/cloudstream3/plugins/CloudstreamPlugin;"

    fun load(file: Path, parent: ClassLoader = PluginLoader::class.java.classLoader): LoadedPlugin {
        val classLoader = AndroidPluginClassLoader(file, parent)
        try {
            val className = findPluginClassName(file)
                ?: throw IllegalArgumentException("No class annotated with @CloudstreamPlugin in $file")
            val pluginClass = classLoader.loadClass(className)
            require(BasePlugin::class.java.isAssignableFrom(pluginClass)) {
                "$className in $file does not extend BasePlugin"
            }
            val instance = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = file.toAbsolutePath().toString()
            val loaded = LoadedPlugin(file, instance, classLoader)
            try {
                if (instance is Plugin) instance.load(DesktopAndroid.activity) else instance.load()
            } catch (t: Throwable) {
                loaded.unload()
                throw t
            }
            return loaded
        } catch (t: Throwable) {
            runCatching { classLoader.close() }
            throw t
        }
    }

    /** The plugin class from manifest.json, or else the top-level class carrying the annotation */
    internal fun findPluginClassName(file: Path): String? = JarFile(file.toFile()).use { jar ->
        jar.getJarEntry("manifest.json")?.let { entry ->
            val manifest = Json.parseToJsonElement(jar.getInputStream(entry).reader().readText()).jsonObject
            manifest["pluginClassName"]?.jsonPrimitive?.content?.let { return@use it }
        }

        // Scan the bytes for the annotation rather than loading every class, which would run their static initializers
        val descriptor = ANNOTATION_DESCRIPTOR.toByteArray()
        jar.entries().asSequence()
            .filter { it.name.endsWith(".class") && !it.name.contains('$') && !it.name.startsWith("META-INF/") }
            .firstOrNull { entry -> jar.getInputStream(entry).use { it.readBytes() }.contains(descriptor) }
            ?.name?.removeSuffix(".class")?.replace('/', '.')
    }

    private fun ByteArray.contains(other: ByteArray): Boolean {
        if (other.isEmpty() || other.size > size) return false
        outer@ for (i in 0..size - other.size) {
            for (j in other.indices) if (this[i + j] != other[j]) continue@outer
            return true
        }
        return false
    }

    /** Keeps the annotation referenced so a rename in the library breaks this file at compile time */
    @Suppress("unused")
    private val annotation = CloudstreamPlugin::class
}
