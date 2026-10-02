package com.lagradost.cloudstream3.plugins

/** The Android app's record of an installed plugin */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
)

/**
 * The parts of the Android app's PluginManager extensions call, mostly to find their own entry.
 * The desktop plugin manager fills in [installed].
 */
object PluginManager {
    @Volatile
    var installed: () -> List<PluginData> = { emptyList() }

    /** Unloads a plugin by its file, set by the desktop plugin manager */
    @Volatile
    var unloader: (String) -> Unit = {}

    fun getPluginsOnline(): Array<PluginData> = installed().toTypedArray()

    /** Desktop has no local plugin folder */
    fun getPluginsLocal(): Array<PluginData> = emptyArray()

    fun unloadPlugin(absolutePath: String) = unloader(absolutePath)
}
