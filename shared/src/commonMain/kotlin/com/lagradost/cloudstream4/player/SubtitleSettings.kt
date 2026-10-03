package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.utils.SubtitleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * How subtitles look: the Android app's SaveCaptionStyle, field for field, so both apps read the
 * same setting. Colors are ARGB, sizes in Android's units: text in sp, edge in px, elevation in dp.
 * The text changes (upper case, removing captions and bloat) are kept for Android but not applied here.
 */
data class SubtitleStyle(
    val foregroundColor: Int = WHITE,
    val backgroundColor: Int = TRANSPARENT,
    val windowColor: Int = TRANSPARENT,
    val edgeType: Int = EDGE_OUTLINE,
    val edgeColor: Int = BLACK,
    /** The Android app's SubtitleFont name, for example "Verdana" or "TimesNewRoman" */
    val font: String? = null,
    val typefaceFilePath: String? = null,
    val elevation: Int = DEFAULT_ELEVATION,
    val fixedTextSize: Float? = null,
    val edgeSize: Float? = null,
    val removeCaptions: Boolean = false,
    val removeBloat: Boolean = true,
    val upperCase: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val backgroundRadius: Float? = null,
    val alignment: Int? = null,
) {
    val textSize: Float get() = fixedTextSize ?: DEFAULT_TEXT_SIZE

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val TRANSPARENT = 0

        // CaptionStyleCompat's edge types
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_DROP_SHADOW = 2
        const val EDGE_RAISED = 3
        const val EDGE_DEPRESSED = 4

        /** The size Android uses when none is set, in sp */
        const val DEFAULT_TEXT_SIZE = 25f
        const val DEFAULT_ELEVATION = 20

        /** The Android app's subtitle fonts, by the name it saves, with the family to look for on desktop */
        val fonts = linkedMapOf(
            "Trebuchet" to "Trebuchet MS",
            "Netflix" to "Netflix Sans",
            "Google" to "Google Sans",
            "Open" to "Open Sans",
            "Futura" to "Futura",
            "Consola" to "Consolas",
            "Gotham" to "Gotham",
            "Lucida" to "Lucida Sans Unicode",
            "STIX" to "STIX Two Text",
            "TimesNewRoman" to "Times New Roman",
            "Verdana" to "Verdana",
            "Ubuntu" to "Ubuntu",
            "Comic" to "Comic Sans MS",
            "Poppins" to "Poppins",
        )
    }
}

/**
 * mpv's options for a style. mpv sizes text against a 720 pixel tall video whatever its real size,
 * so Android's sizes are scaled to look about the same on a phone and a monitor: its 25 sp default
 * becomes mpv's own default of 38. Subtitles with styles of their own (ASS) take this one instead,
 * as on Android: release groups often ship plain dialogue as ASS, which would otherwise ignore it.
 */
fun SubtitleStyle.mpvOptions(): List<Pair<String, String>> {
    val edge = (edgeSize ?: 4f) / 2
    val (outline, shadow) = when (edgeType) {
        SubtitleStyle.EDGE_NONE -> 0f to 0f
        SubtitleStyle.EDGE_DROP_SHADOW -> 0f to edge
        SubtitleStyle.EDGE_RAISED, SubtitleStyle.EDGE_DEPRESSED -> edge / 2 to 1.5f
        else -> edge to 0f
    }
    // Android draws the background behind the text and the window behind the whole cue: mpv has one box
    val box = backgroundColor.takeIf { alpha(it) > 0 } ?: windowColor.takeIf { alpha(it) > 0 }
    return listOf(
        "sub-font" to (font?.let { SubtitleStyle.fonts[it] } ?: "sans-serif"),
        "sub-font-size" to (textSize * 1.5f).roundToInt().toString(),
        "sub-color" to mpvColor(foregroundColor),
        "sub-bold" to yesNo(bold),
        "sub-italic" to yesNo(italic),
        "sub-border-style" to if (box != null) "background-box" else "outline-and-shadow",
        "sub-back-color" to mpvColor(box ?: SubtitleStyle.TRANSPARENT),
        "sub-outline-size" to outline.toString(),
        "sub-outline-color" to mpvColor(edgeColor),
        "sub-shadow-offset" to shadow.toString(),
        "sub-shadow-color" to mpvColor(edgeColor),
        "sub-margin-y" to (elevation * 1.7f).roundToInt().coerceAtLeast(0).toString(),
        "sub-ass-override" to "force",
    )
}

private fun alpha(argb: Int) = argb ushr 24

/** mpv reads #AARRGGBB */
private fun mpvColor(argb: Int) = "#" + argb.toUInt().toString(16).padStart(8, '0').uppercase()

private fun yesNo(value: Boolean) = if (value) "yes" else "no"

/** Languages as the Android app names them: IETF tags such as "en" or "pt-BR" */
object SubtitleLanguages {
    /** A subtitle's language, from a name ("English", "English SDH") or a code ("en", "eng") */
    fun tagOf(language: String?): String? = SubtitleHelper.fromLanguageToTagIETF(language)
        ?: SubtitleHelper.fromCodeToLangTagIETF(language)
        ?: SubtitleHelper.fromLanguageToTagIETF(language, halfMatch = true)

    /** The codes a video file may use for a language, which mpv matches its tracks with */
    fun codesOf(tag: String): List<String> {
        val language = SubtitleHelper.languages.firstOrNull { it.IETF_tag == tag } ?: return listOf(tag)
        return listOf(language.IETF_tag, language.ISO_639_1, language.ISO_639_2_B, language.ISO_639_3)
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

/**
 * The subtitle settings: how they look and the language picked on its own. The player applies a
 * change right away.
 */
interface SubtitleSettingsStore {
    val style: StateFlow<SubtitleStyle>

    fun setStyle(style: SubtitleStyle)

    /** The language whose subtitles show on their own, as an IETF tag, or null for none */
    val autoSelect: StateFlow<String?>

    fun setAutoSelect(tag: String?)

    companion object {
        /** The Android app's default: English */
        const val DEFAULT_AUTO_SELECT = "en"
    }
}

class InMemorySubtitleSettings(
    style: SubtitleStyle = SubtitleStyle(),
    autoSelect: String? = SubtitleSettingsStore.DEFAULT_AUTO_SELECT,
) : SubtitleSettingsStore {
    private val _style = MutableStateFlow(style)
    override val style: StateFlow<SubtitleStyle> = _style.asStateFlow()

    override fun setStyle(style: SubtitleStyle) {
        _style.value = style
    }

    private val _autoSelect = MutableStateFlow(autoSelect)
    override val autoSelect: StateFlow<String?> = _autoSelect.asStateFlow()

    override fun setAutoSelect(tag: String?) {
        _autoSelect.value = tag
    }
}
