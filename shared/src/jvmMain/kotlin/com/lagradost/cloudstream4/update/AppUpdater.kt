package com.lagradost.cloudstream4.update

import com.lagradost.cloudstream4.AppData
import com.lagradost.cloudstream4.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.lagradost.cloudstream4.network.DesktopHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

/** A published version of the app with its Windows installer */
data class AppRelease(
    val version: String,
    val notes: String?,
    val pageUrl: String,
    val installerUrl: String,
    val installerName: String,
    /** The installer's SHA-256 as GitHub lists it, when it does */
    val sha256: String?,
)

class AppUpdateException(message: String) : Exception(message)

/**
 * Finds newer versions of the app in the fork's GitHub releases and installs them with the release's
 * MSI, which upgrades the installed app in place because every version shares one upgrade code.
 */
class AppUpdater(
    private val releasesUrl: String = LATEST_RELEASE,
    private val http: OkHttpClient = DesktopHttp.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    /** The newest release when it is newer than this app, otherwise null. Also null when nothing is published yet */
    suspend fun findUpdate(current: String = AppVersion.current): AppRelease? {
        val release = latestRelease() ?: return null
        return release.takeIf { AppVersion.isNewer(it.version, current) }
    }

    suspend fun latestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(releasesUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            // GitHub answers 404 while the repository has no release
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw AppUpdateException("GitHub answered HTTP ${response.code}")
            parseRelease(response.body.string())
        }
    }

    /** Downloads the installer into [dir] and checks it against the release's hash. Returns its path */
    suspend fun download(release: AppRelease, dir: Path, onProgress: (Float) -> Unit = {}): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val target = dir.resolve(release.installerName)
        target.deleteIfExists()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(release.installerUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw AppUpdateException("Download failed: HTTP ${response.code}")
            val total = response.body.contentLength()
            var done = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !actual.equals(release.sha256, ignoreCase = true)) {
            target.deleteIfExists()
            throw AppUpdateException("The download does not match the release's SHA-256")
        }
        target
    }

    /**
     * Starts the installer and returns, the caller then quits so the installer can replace the app.
     * /passive shows only a progress bar and keeps the folder the app is installed in.
     */
    fun startInstaller(installer: Path, appData: AppData = AppData.instance) {
        appData.markUpdating()
        ProcessBuilder("msiexec", "/i", installer.toAbsolutePath().toString(), "/passive").start()
    }

    companion object {
        const val REPOSITORY = "harsh-pockt/cloudstream"
        const val LATEST_RELEASE = "https://api.github.com/repos/$REPOSITORY/releases/latest"

        internal fun parseRelease(text: String): AppRelease? {
            val obj = Json.parseToJsonElement(text).jsonObject
            val tag = obj.string("tag_name") ?: return null
            val installer = obj["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it.string("name")?.endsWith(".msi", ignoreCase = true) == true }
                ?: return null
            return AppRelease(
                version = tag.removePrefix("v"),
                notes = obj.string("body"),
                pageUrl = obj.string("html_url") ?: "https://github.com/$REPOSITORY/releases",
                installerUrl = installer.string("browser_download_url") ?: return null,
                installerName = installer.string("name")!!,
                // GitHub lists asset digests as "sha256:<hex>"
                sha256 = installer.string("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
