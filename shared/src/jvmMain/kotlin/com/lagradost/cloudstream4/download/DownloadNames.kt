package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.getFolderPrefix
import com.lagradost.cloudstream3.isEpisodeBased

/** The Android app's download file and folder names, from DownloadFileManagement, in English */
object DownloadNames {
    private val reserved = Regex("""[|\\?*<":>+\[\]/']""")

    /** Characters Windows does not allow in a name become spaces, as on Android */
    fun sanitize(name: String): String =
        name.replace(reserved, " ").replace(Regex("""\p{Cntrl}"""), " ").replace(Regex(" {2,}"), " ").trim()
            // Windows drops a trailing dot, which would make the file impossible to find again
            .trimEnd('.', ' ')

    /** "Movies", or "TVSeries/<title>" for a type with episodes */
    fun folder(type: TvType, title: String): String {
        val prefix = type.getFolderPrefix()
        return if (type.isEpisodeBased()) "$prefix/${sanitize(title)}" else prefix
    }

    /**
     * The file name without its extension: the title for a movie, "Season 1 Episode 2 - Pilot",
     * "Episode 2 - Pilot", "Season 1 Episode 2" or "Episode 2" for an episode
     */
    fun fileName(title: String, episode: Int?, season: Int?, episodeName: String?): String {
        if (episode == null) return sanitize(title)
        val number = listOfNotNull(season?.let { "Season $it" }, "Episode $episode").joinToString(" ")
        val name = episodeName?.takeIf { it.isNotBlank() }
        return sanitize(if (name != null) "$number - $name" else number)
    }
}
