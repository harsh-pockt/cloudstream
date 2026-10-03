package com.lagradost.cloudstream4.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream4.generated.resources.*
import com.lagradost.cloudstream4.player.DataStoreSubtitleSettings
import com.lagradost.cloudstream4.player.SubtitleSettingsStore
import com.lagradost.cloudstream4.player.SubtitleStyle
import com.mihon.common.preference.PreferenceData
import com.mihon.presentation.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The subtitle settings, in the Android app's keys: how subtitles look and which language shows on
 * its own. An open player applies a change right away.
 */
@Composable
fun subtitlePreferences(store: SubtitleSettingsStore = DataStoreSubtitleSettings.instance): Preference.PreferenceGroup {
    val style by store.style.collectAsState()
    val fields = remember(store) { SubtitleFields(store) }
    val normal = stringResource(Res.string.normal)
    val fonts = remember { installedFonts() }

    return Preference.PreferenceGroup(
        title = stringResource(Res.string.subtitles_settings),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.CustomPreference(title = "") { SubtitlePreview(style, stringResource(Res.string.subtitles_example_text)) },
            Preference.PreferenceItem.ListPreference(
                preference = fields.autoSelect,
                entries = mapOf<String?, String>(null to stringResource(Res.string.none)) +
                        SubtitleHelper.languages.sortedBy { it.languageName }.associate { it.IETF_tag to it.languageName },
                title = stringResource(Res.string.subs_auto_select_language),
                subtitle = "%s · shows on its own when a video has it",
                icon = painterResource(Res.drawable.ic_baseline_subtitles_24),
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = fields.textSize,
                title = stringResource(Res.string.subs_font_size),
                valueRange = 10..60,
                valueString = style.fixedTextSize?.toInt()?.toString() ?: "$normal (${SubtitleStyle.DEFAULT_TEXT_SIZE.toInt()})",
            ),
            Preference.PreferenceItem.ListPreference(
                preference = fields.font,
                // Fonts the Android app has but this computer lacks are left out, unless one is picked already
                entries = mapOf<String?, String>(null to normal) + SubtitleStyle.fonts
                    .filter { (name, family) -> family in fonts || name == style.font }
                    .mapValues { (_, family) -> if (family in fonts) family else "$family (not installed)" },
                title = stringResource(Res.string.subs_font),
            ),
            Preference.PreferenceItem.SwitchPreference(preference = fields.bold, title = stringResource(Res.string.all_subtitles_bold)),
            Preference.PreferenceItem.SwitchPreference(preference = fields.italic, title = stringResource(Res.string.all_subtitles_italic)),
            Preference.PreferenceItem.ColorPreference(
                preference = fields.textColor,
                title = stringResource(Res.string.subs_text_color),
                icon = painterResource(Res.drawable.colors_24px),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = fields.edgeType,
                entries = mapOf(
                    SubtitleStyle.EDGE_NONE to stringResource(Res.string.none),
                    SubtitleStyle.EDGE_OUTLINE to stringResource(Res.string.subtitles_outline),
                    SubtitleStyle.EDGE_DROP_SHADOW to stringResource(Res.string.subtitles_shadow),
                    SubtitleStyle.EDGE_RAISED to stringResource(Res.string.subtitles_raised),
                    SubtitleStyle.EDGE_DEPRESSED to stringResource(Res.string.subtitles_depressed),
                ),
                title = stringResource(Res.string.subs_edge_type),
                icon = painterResource(Res.drawable.format_paint_24px),
            ),
            Preference.PreferenceItem.ColorPreference(preference = fields.edgeColor, title = stringResource(Res.string.subs_outline_color)),
            Preference.PreferenceItem.ColorPreference(
                preference = fields.backgroundColor,
                title = stringResource(Res.string.subs_background_color),
                subtitle = "A box behind the text. Fully transparent for none",
                icon = painterResource(Res.drawable.palette_24px),
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = fields.elevation,
                title = stringResource(Res.string.subs_subtitle_elevation),
                subtitle = "Distance from the bottom of the video",
                valueRange = 0..100,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(Res.string.reset_btn),
                subtitle = "Back to how subtitles look at first",
                icon = painterResource(Res.drawable.ic_baseline_replay_24),
                // The text changes only the Android app applies stay as they are
                onClick = {
                    val now = store.style.value
                    store.setStyle(SubtitleStyle(removeCaptions = now.removeCaptions, removeBloat = now.removeBloat, upperCase = now.upperCase))
                },
            ),
        ),
    )
}

/** The fonts installed on this computer, by family */
private fun installedFonts(): Set<String> = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
}.getOrDefault(emptySet())

/** Each setting as a preference the settings widgets can show and change */
private class SubtitleFields(private val store: SubtitleSettingsStore) {
    private fun <T> field(key: String, read: (SubtitleStyle) -> T, write: SubtitleStyle.(T) -> SubtitleStyle) =
        StorePreference(key, store.style, read, { store.setStyle(store.style.value.write(it)) }, read(SubtitleStyle()))

    val autoSelect = StorePreference("subs_auto_select", store.autoSelect, { it }, store::setAutoSelect, SubtitleSettingsStore.DEFAULT_AUTO_SELECT)
    val textSize = field("fixedTextSize", { it.textSize.toInt() }) { copy(fixedTextSize = it.toFloat()) }
    val font = field("font", { it.font }) { copy(font = it, typefaceFilePath = null) }
    val bold = field("bold", { it.bold }) { copy(bold = it) }
    val italic = field("italic", { it.italic }) { copy(italic = it) }
    val textColor = field("foregroundColor", { it.foregroundColor }) { copy(foregroundColor = it) }
    val edgeType = field("edgeType", { it.edgeType }) { copy(edgeType = it) }
    val edgeColor = field("edgeColor", { it.edgeColor }) { copy(edgeColor = it) }
    val backgroundColor = field("backgroundColor", { it.backgroundColor }) { copy(backgroundColor = it) }
    val elevation = field("elevation", { it.elevation }) { copy(elevation = it) }
}

/** A part of a value held in a [StateFlow], read and written as a preference */
private class StorePreference<S, T>(
    private val key: String,
    private val source: StateFlow<S>,
    private val read: (S) -> T,
    private val write: (T) -> Unit,
    private val default: T,
) : PreferenceData<T> {
    override fun key() = key
    override fun get() = read(source.value)
    override fun set(value: T) = write(value)
    override fun isSet() = get() != default
    override fun delete() = write(default)
    override fun defaultValue() = default
    override fun changes(): Flow<T> = source.map(read).distinctUntilChanged()
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
}

/** The example text as the player shows it, over a dark frame */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun SubtitlePreview(style: SubtitleStyle, text: String) {
    val family = style.font?.let { SubtitleStyle.fonts[it] }?.let { FontFamily(SystemFont(it)) } ?: FontFamily.SansSerif
    val base = TextStyle(
        fontSize = (style.textSize * 0.8f).sp,
        fontFamily = family,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
    )
    val edge = Color(style.edgeColor)
    val shadow = when (style.edgeType) {
        SubtitleStyle.EDGE_DROP_SHADOW -> Shadow(edge, Offset(3f, 3f), 2f)
        SubtitleStyle.EDGE_RAISED -> Shadow(edge, Offset(1.5f, 1.5f), 0f)
        SubtitleStyle.EDGE_DEPRESSED -> Shadow(edge, Offset(-1.5f, -1.5f), 0f)
        else -> null
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4A5E), Color(0xFF151A22)))),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (style.elevation * 0.6f).dp.coerceAtMost(100.dp), start = 16.dp, end = 16.dp)
                .background(Color(style.backgroundColor).takeIf { it.alpha > 0f } ?: Color(style.windowColor)),
        ) {
            if (style.edgeType == SubtitleStyle.EDGE_OUTLINE) {
                Text(text, style = base.copy(color = edge, drawStyle = Stroke(width = (style.edgeSize ?: 4f))))
            }
            Text(text, style = base.copy(color = Color(style.foregroundColor), shadow = shadow))
        }
    }
}
