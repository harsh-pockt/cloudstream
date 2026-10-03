package com.lagradost.cloudstream4.download

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream4.android.DesktopAndroid
import java.io.File

/** A title with downloads: a movie, or a series with its downloaded episodes */
data class DownloadedTitle(
    val id: Int,
    val apiName: String,
    val url: String,
    val name: String,
    val type: TvType,
    val poster: String?,
)

/** One downloaded or downloading file: an episode, or a movie with [episode] null */
data class DownloadedEpisode(
    val id: Int,
    val parentId: Int,
    val name: String?,
    val episode: Int?,
    val season: Int?,
    val poster: String?,
    val description: String?,
)

/** Where a download is saved and how big it will be */
data class DownloadFile(
    /** The full size once done, an estimate while an HLS stream downloads */
    val totalBytes: Long,
    /** The downloads folder picked in the settings when it started */
    val root: File,
    /** For example "TVSeries/<title>", with forward slashes as on Android */
    val relativePath: String,
    val displayName: String,
    /** The HLS segments written so far, to carry on from there */
    val segmentsDone: Int? = null,
    /** The file's length when [segmentsDone] was saved: a segment written after it is cut off on resume */
    val segmentBytes: Long? = null,
    /** The url hash of the link it came from: carrying on with another link would mix two files */
    val linkHash: Int? = null,
    /** The name of the source the user picked, to find it again when its url has changed */
    val linkName: String? = null,
) {
    val folder: File get() = if (relativePath.isBlank()) root else root.resolve(relativePath)
    val file: File get() = folder.resolve(displayName)
}

/** What finds the links of a download again, to resume it after the app restarts */
data class DownloadSource(val apiName: String, val data: String)

/**
 * Downloads in the Android app's keys and JSON, so its download list and the desktop's read the same
 * data: the title in "download_header_cache", the episode in "download_episode_cache/<title id>" and
 * the file in "download_info". Desktop adds "download_desktop_source" to find the links again.
 */
class DownloadStore(private val context: Context = DesktopAndroid.application) {
    /** The Android app's DownloadHeaderCached, also written by the watch store for Continue watching */
    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class HeaderCached(
        @JsonProperty("apiName") val apiName: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("type") val type: TvType,
        @JsonProperty("name") val name: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("cacheTime") val cacheTime: Long,
        @JsonProperty("id") val id: Int,
    )

    /** The Android app's DownloadEpisodeCached. A movie is saved as episode 0 under its own id */
    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class EpisodeCached(
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("episode") val episode: Int,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("parentId") val parentId: Int,
        @JsonProperty("description") val description: String?,
        @JsonProperty("cacheTime") val cacheTime: Long,
        @JsonProperty("id") val id: Int,
    )

    /** The Android app's DownloadedFileInfo, with an absolute folder as its base path */
    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class FileInfo(
        @JsonProperty("totalBytes") val totalBytes: Long,
        @JsonProperty("relativePath") val relativePath: String,
        @JsonProperty("displayName") val displayName: String,
        @JsonProperty("extraInfo") val extraInfo: String? = null,
        @JsonProperty("basePath") val basePath: String? = null,
        @JsonProperty("linkHash") val linkHash: Int? = null,
        /** Desktop only, Android ignores it */
        @JsonProperty("segmentBytes") val segmentBytes: Long? = null,
        /** Desktop only */
        @JsonProperty("linkName") val linkName: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class Source(
        @JsonProperty("apiName") val apiName: String,
        @JsonProperty("data") val data: String,
    )

    fun saveTitle(title: DownloadedTitle) {
        context.setKey("$HEADER/${title.id}", HeaderCached(title.apiName, title.url, title.type, title.name, title.poster, System.currentTimeMillis(), title.id))
    }

    fun title(id: Int): DownloadedTitle? = context.getKey("$HEADER/$id", HeaderCached::class.java)
        ?.let { DownloadedTitle(it.id, it.apiName, it.url, it.name, it.type, it.poster) }

    fun saveEpisode(episode: DownloadedEpisode, source: DownloadSource) {
        context.setKey(
            "$EPISODE/${episode.parentId}/${episode.id}",
            EpisodeCached(episode.name, episode.poster, episode.episode ?: 0, episode.season, episode.parentId, episode.description, System.currentTimeMillis(), episode.id),
        )
        context.setKey("$SOURCE/${episode.id}", Source(source.apiName, source.data))
    }

    /** The episodes saved under a title, whether or not their file is there */
    fun episodes(parentId: Int): List<DownloadedEpisode> {
        val movie = title(parentId)?.type?.isEpisodeBased() == false
        return context.getKeys("$EPISODE/$parentId").mapNotNull { key ->
            val cached = context.getKey(key, EpisodeCached::class.java) ?: return@mapNotNull null
            // A movie is the title's own id, saved as episode 0
            val isMovie = movie && cached.id == parentId
            DownloadedEpisode(cached.id, cached.parentId, cached.name, cached.episode.takeUnless { isMovie }, cached.season, cached.poster, cached.description)
        }
    }

    fun episode(parentId: Int, id: Int): DownloadedEpisode? = episodes(parentId).firstOrNull { it.id == id }

    /** The ids of every title with episodes saved */
    fun titleIds(): Set<Int> = context.getKeys(EPISODE).mapNotNull { it.split('/').getOrNull(1)?.toIntOrNull() }.toSet()

    fun source(id: Int): DownloadSource? = context.getKey("$SOURCE/$id", Source::class.java)?.let { DownloadSource(it.apiName, it.data) }

    fun saveFile(id: Int, file: DownloadFile) {
        context.setKey(
            "$INFO/$id",
            FileInfo(file.totalBytes, file.relativePath, file.displayName, file.segmentsDone?.toString(), file.root.absolutePath, file.linkHash, file.segmentBytes, file.linkName),
        )
    }

    fun file(id: Int): DownloadFile? {
        val info = context.getKey("$INFO/$id", FileInfo::class.java) ?: return null
        // Android keeps a content:// uri or nothing for its own Downloads folder, which desktop cannot use
        val root = info.basePath?.takeUnless { it.isBlank() || it.startsWith("content:") }?.let(::File) ?: return null
        return DownloadFile(info.totalBytes, root, info.relativePath, info.displayName, info.extraInfo?.toIntOrNull(), info.segmentBytes, info.linkHash, info.linkName)
    }

    /** Forgets the file and the episode. The title stays, Continue watching may use it */
    fun remove(parentId: Int, id: Int) {
        context.removeKey("$INFO/$id")
        context.removeKey("$SOURCE/$id")
        context.removeKey("$EPISODE/$parentId/$id")
    }

    companion object {
        private const val HEADER = "download_header_cache"
        private const val EPISODE = "download_episode_cache"
        private const val INFO = "download_info"
        private const val SOURCE = "download_desktop_source"
    }
}
