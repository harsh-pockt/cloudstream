package com.lagradost.cloudstream4

import com.lagradost.cloudstream4.update.AppUpdater
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDataTest {
    private val dir: Path = Files.createTempDirectory("cs-appdata")
    private val root = dir.resolve("root")
    private val cache = dir.resolve("cache")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun file(path: Path) = path.also { it.parent.createDirectories(); it.writeText("x") }

    private fun fillData() {
        file(root.resolve("settings.json"))
        file(root.resolve("plugins/A.jar"))
        file(root.resolve("android/shared_prefs/a.json"))
        file(root.resolve("android/shared_prefs/rebuild_preference.json"))
        file(root.resolve("android/cache/c"))
        file(cache.resolve("posters/p.jpg"))
    }

    @Test
    fun eachResetDeletesOnlyItsPart() {
        fillData()
        val data = AppData(root, cache, installedExecutable = null)

        data.reset(AppData.Reset.CACHE)
        assertFalse(cache.resolve("posters").exists())
        assertFalse(root.resolve("android/cache/c").exists())
        assertTrue(root.resolve("plugins/A.jar").exists())

        data.reset(AppData.Reset.EXTENSIONS)
        assertFalse(root.resolve("plugins").exists())
        assertFalse(root.resolve("android/shared_prefs/a.json").exists())
        assertTrue(root.resolve("android/shared_prefs/rebuild_preference.json").exists(), "The library stays")
        assertTrue(root.resolve("settings.json").exists())

        data.reset(AppData.Reset.EVERYTHING)
        assertFalse(data.hasData())
    }

    @Test
    fun scheduledResetRunsOnTheNextStartOnly() {
        fillData()
        val data = AppData(root, cache, installedExecutable = null)
        data.scheduleReset(AppData.Reset.EVERYTHING)
        assertTrue(root.resolve("settings.json").exists())

        data.runPendingReset()
        assertFalse(root.resolve("settings.json").exists())
        file(root.resolve("settings.json"))
        data.runPendingReset()
        assertTrue(root.resolve("settings.json").exists(), "A reset runs once")
    }

    @Test
    fun reinstallIsNoticedButAnUpdateThroughTheAppIsNot() {
        fillData()
        val exe = file(dir.resolve("install1/CloudStream.exe"))
        val first = AppData(root, cache, exe)
        assertFalse(first.isReinstallWithOldData(), "The first run only notes the install")
        first.rememberInstall()
        assertFalse(AppData(root, cache, exe).isReinstallWithOldData(), "Same install")

        // Installing again creates the executable again, with a new creation time
        Thread.sleep(20)
        val reinstalled = file(dir.resolve("install2/CloudStream.exe"))
        assertTrue(AppData(root, cache, reinstalled).isReinstallWithOldData())

        first.markUpdating()
        val updated = AppData(root, cache, reinstalled)
        assertFalse(updated.isReinstallWithOldData(), "An update the app started")
        updated.rememberInstall()
        assertFalse(AppData(root, cache, reinstalled).isReinstallWithOldData())
    }

    @Test
    fun reinstallWithoutDataDoesNotAsk() {
        val exe = file(dir.resolve("install1/CloudStream.exe"))
        AppData(root, cache, exe).rememberInstall()
        Thread.sleep(20)
        val reinstalled = file(dir.resolve("install2/CloudStream.exe"))
        assertFalse(AppData(root, cache, reinstalled).isReinstallWithOldData())
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(AppVersion.isNewer("4.10.0", "4.9.2"))
        assertTrue(AppVersion.isNewer("v4.8.1", "4.8.0"))
        assertFalse(AppVersion.isNewer("4.8.0", "4.8.0"))
        assertFalse(AppVersion.isNewer("4.7.9", "4.8"))
    }

    @Test
    fun aPreReleaseComesBeforeItsVersion() {
        // The installed beta.1 says 4.8.0: offering beta.1 again would install it over and over
        assertFalse(AppVersion.isNewer("4.8.0-beta.1", "4.8.0"))
        assertTrue(AppVersion.isNewer("4.8.0", "4.8.0-beta.1"))
        assertFalse(AppVersion.isNewer("4.8.0-beta.1", "4.8.0-beta.1"))
        assertTrue(AppVersion.isNewer("v4.8.0-beta.2", "4.8.0-beta.1"))
        assertTrue(AppVersion.isNewer("4.8.0-beta.10", "4.8.0-beta.9"))
        assertTrue(AppVersion.isNewer("4.8.0-rc.1", "4.8.0-beta.3"))
        assertTrue(AppVersion.isNewer("4.8.1-beta.1", "4.8.0"))
        assertFalse(AppVersion.isNewer("4.8.0-beta", "4.8.0-beta.1"))
    }

    @Test
    fun releaseIsReadFromGitHub() {
        val release = AppUpdater.parseRelease(
            """{"tag_name":"v4.9.0","html_url":"https://github.com/r/releases/tag/v4.9.0","body":"Notes",
              "assets":[{"name":"libmpv-source.zip","browser_download_url":"https://x/src.zip"},
                        {"name":"CloudStream-4.9.0.msi","browser_download_url":"https://x/c.msi","digest":"sha256:abc123"}]}"""
        )!!
        assertEquals("4.9.0", release.version)
        assertEquals("https://x/c.msi", release.installerUrl)
        assertEquals("abc123", release.sha256)
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1","assets":[]}"""), "No installer, no update")
    }

    @Test
    fun theNewestAppReleaseIsPickedIncludingPreReleases() {
        fun release(tag: String, prerelease: Boolean = true, draft: Boolean = false, msi: Boolean = true) =
            """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"html_url":"https://x/$tag","assets":[""" +
                (if (msi) """{"name":"CloudStream.msi","browser_download_url":"https://x/$tag.msi"}""" else "") + "]}"
        val list = listOf(
            release("libmpv-20261003-git-3186d369f9"),
            release("v4.10.0", draft = true),
            release("v4.8.1", prerelease = false),
            release("v4.9.0"),
            release("v4.9.1", msi = false),
        ).joinToString(",", "[", "]")
        assertEquals("4.9.0", AppUpdater.newestRelease(list)?.version)
        assertNull(AppUpdater.newestRelease("[]"))
    }
}
