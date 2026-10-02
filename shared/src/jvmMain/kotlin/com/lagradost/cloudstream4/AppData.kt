package com.lagradost.cloudstream4

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Erasing the app's data, and noticing when the app was installed again so the user can choose to
 * start fresh.
 *
 * Erasing happens when the app starts, before anything opens these files: a loaded extension keeps
 * its jar locked on Windows, and the settings file is kept open. So the reset options in Settings
 * only schedule a reset and restart the app.
 */
class AppData(
    private val root: Path,
    private val cache: Path,
    /** The installed app's executable, null when running from a build */
    private val installedExecutable: Path? = System.getProperty("jpackage.app-path")?.let(Path::of),
) {
    enum class Reset(val title: String) {
        /** Posters and other downloaded files the app can fetch again */
        CACHE("Clear the cache"),

        /** Installed extensions and their data. Repositories and settings stay */
        EXTENSIONS("Remove all extensions"),

        /** Everything, as if the app had never been used */
        EVERYTHING("Erase all data"),
    }

    private val pendingFile = root.resolve("reset-pending")
    private val installFile = root.resolve("install-id")
    private val updatingFile = root.resolve("updating")

    /** Erases the data on the next start, see [runPendingReset] */
    fun scheduleReset(reset: Reset) {
        root.createDirectories()
        pendingFile.writeText(reset.name)
    }

    /** Erases what [scheduleReset] asked for. Call first thing at startup */
    fun runPendingReset() {
        if (!pendingFile.exists()) return
        val reset = runCatching { Reset.valueOf(pendingFile.readText().trim()) }.getOrNull()
        pendingFile.deleteIfExists()
        reset?.let(::reset)
    }

    /**
     * Erases right away. Only safe before the app has opened any of these files. After a restart the
     * old app may still be closing and holding files, so locked files are tried again for a few seconds.
     */
    fun reset(reset: Reset) {
        repeat(RETRIES) { attempt ->
            if (resetOnce(reset)) return
            if (attempt < RETRIES - 1) Thread.sleep(500)
        }
        println("WARNING AppData: Some files could not be deleted for ${reset.name}")
    }

    /** Returns true when everything was deleted */
    private fun resetOnce(reset: Reset): Boolean {
        var complete = true
        // The install id is kept, it describes the installed app, not its data
        val keep = setOf(installFile.name)
        fun delete(path: Path) {
            if (path.exists() && !path.toFile().deleteRecursively()) complete = false
        }
        fun deleteContents(dir: Path) {
            if (dir.isDirectory()) dir.listDirectoryEntries().forEach(::delete)
        }
        when (reset) {
            Reset.CACHE -> {
                deleteContents(cache)
                deleteContents(root.resolve("android").resolve("cache"))
            }
            Reset.EXTENSIONS -> {
                delete(root.resolve("plugins"))
                delete(root.resolve("android"))
            }
            Reset.EVERYTHING -> {
                deleteContents(cache)
                if (root.isDirectory()) root.listDirectoryEntries().filter { it.name !in keep }.forEach(::delete)
            }
        }
        return complete
    }

    /** True when the app has data from earlier use, such as settings or extensions */
    fun hasData(): Boolean = root.isDirectory() &&
        root.listDirectoryEntries().any { it.name !in setOf(installFile.name, updatingFile.name, "logs") }

    /** Called before the app starts an installer to update itself, so the next start does not ask to start fresh */
    fun markUpdating() {
        root.createDirectories()
        updatingFile.writeText("1")
    }

    /**
     * True when the app was installed again since it last ran and data from before is there: then the
     * user is asked whether to keep it. Updates through the app do not count. The first time a version
     * with this check runs it only notes the install.
     */
    fun isReinstallWithOldData(): Boolean {
        val current = currentInstallId() ?: return false
        val updating = updatingFile.exists()
        val previous = if (installFile.exists()) installFile.readText().trim() else null
        return previous != null && previous != current && !updating && hasData()
    }

    /** Notes the current install, call after [isReinstallWithOldData] was answered */
    fun rememberInstall() {
        updatingFile.deleteIfExists()
        val current = currentInstallId() ?: return
        root.createDirectories()
        installFile.writeText(current)
    }

    /** Reinstalling recreates the executable, so its creation time tells installs apart */
    private fun currentInstallId(): String? {
        val exe = installedExecutable?.takeIf { it.exists() } ?: return null
        return Files.readAttributes(exe, BasicFileAttributes::class.java).creationTime().toMillis().toString()
    }

    companion object {
        private const val RETRIES = 10

        val instance: AppData by lazy { AppData(AppDirs.root, AppDirs.cache) }
    }
}

/** The app's version, set by the build as cloudstream.version and by the installer as jpackage.app-version */
object AppVersion {
    val current: String =
        System.getProperty("cloudstream.version") ?: System.getProperty("jpackage.app-version") ?: "0.0.0"

    /** Compares dotted versions such as 4.10.0 and 4.9.2 number by number. A leading "v" is ignored */
    fun isNewer(candidate: String, than: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(than)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
