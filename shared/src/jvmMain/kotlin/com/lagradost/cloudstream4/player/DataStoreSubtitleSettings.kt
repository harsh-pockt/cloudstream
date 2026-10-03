package com.lagradost.cloudstream4.player

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream4.android.DesktopAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Subtitle settings in the Android app's keys and JSON: "subtitle_settings" for the style and
 * "subs_auto_select" for the language, next to the library in the same store.
 */
class DataStoreSubtitleSettings(
    private val context: Context = DesktopAndroid.application,
) : SubtitleSettingsStore {
    /** The Android app's SaveCaptionStyle */
    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class SaveCaptionStyle(
        @JsonProperty("foregroundColor") val foregroundColor: Int,
        @JsonProperty("backgroundColor") val backgroundColor: Int,
        @JsonProperty("windowColor") val windowColor: Int,
        @JsonProperty("edgeType") val edgeType: Int,
        @JsonProperty("edgeColor") val edgeColor: Int,
        @JsonProperty("font") val font: String? = null,
        @JsonProperty("typefaceFilePath") val typefaceFilePath: String?,
        @JsonProperty("elevation") val elevation: Int,
        @JsonProperty("fixedTextSize") val fixedTextSize: Float?,
        @JsonProperty("edgeSize") val edgeSize: Float? = null,
        @JsonProperty("removeCaptions") val removeCaptions: Boolean = false,
        @JsonProperty("removeBloat") val removeBloat: Boolean = true,
        @JsonProperty("upperCase") val upperCase: Boolean = false,
        @JsonProperty("bold") val bold: Boolean = false,
        @JsonProperty("italic") val italic: Boolean = false,
        @JsonProperty("backgroundRadius") val backgroundRadius: Float? = null,
        @JsonProperty("alignment") val alignment: Int? = null,
    ) {
        fun toStyle() = SubtitleStyle(
            foregroundColor, backgroundColor, windowColor, edgeType, edgeColor, font, typefaceFilePath, elevation,
            fixedTextSize, edgeSize, removeCaptions, removeBloat, upperCase, bold, italic, backgroundRadius, alignment,
        )

        companion object {
            fun of(style: SubtitleStyle) = with(style) {
                SaveCaptionStyle(
                    foregroundColor, backgroundColor, windowColor, edgeType, edgeColor, font, typefaceFilePath, elevation,
                    fixedTextSize, edgeSize, removeCaptions, removeBloat, upperCase, bold, italic, backgroundRadius, alignment,
                )
            }
        }
    }

    private val _style = MutableStateFlow(
        runCatching { context.getKey(STYLE, SaveCaptionStyle::class.java)?.toStyle() }.getOrNull() ?: SubtitleStyle()
    )
    override val style: StateFlow<SubtitleStyle> = _style.asStateFlow()

    override fun setStyle(style: SubtitleStyle) {
        context.setKey(STYLE, SaveCaptionStyle.of(style))
        _style.value = style
    }

    // Android saves its "None" entry as the word itself, in the app's language: anything that is not a
    // language counts as none
    private val _autoSelect = MutableStateFlow(
        context.getKey(AUTO_SELECT, String::class.java).let { saved ->
            if (saved == null) SubtitleSettingsStore.DEFAULT_AUTO_SELECT
            else saved.takeIf { tag -> SubtitleHelper.languages.any { it.IETF_tag == tag } }
        }
    )
    override val autoSelect: StateFlow<String?> = _autoSelect.asStateFlow()

    override fun setAutoSelect(tag: String?) {
        context.setKey(AUTO_SELECT, tag ?: NONE)
        _autoSelect.value = tag
    }

    companion object {
        private const val STYLE = "subtitle_settings"
        private const val AUTO_SELECT = "subs_auto_select"

        /** What the Android app saves for no language, in English */
        private const val NONE = "None"

        val instance: DataStoreSubtitleSettings by lazy { DataStoreSubtitleSettings() }
    }
}
