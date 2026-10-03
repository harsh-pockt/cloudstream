package com.lagradost.cloudstream4.player

import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream4.android.DesktopAndroid
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleSettingsTest {
    private val dir = Files.createTempDirectory("cs-subtitles")

    init {
        DesktopAndroid.init(dir)
    }

    /** The saved preferences once they hold [text]: they are written to disk in the background */
    private fun savedWith(text: String): String {
        val file = dir.resolve("shared_prefs/rebuild_preference.json")
        fun read() = runCatching { file.readText() }.getOrDefault("")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && text !in read()) Thread.sleep(50)
        return read()
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun savesInTheAndroidAppsKeysAndReadsBack() {
        val settings = DataStoreSubtitleSettings()
        assertEquals(SubtitleStyle(), settings.style.value)
        assertEquals("en", settings.autoSelect.value)

        val style = SubtitleStyle(foregroundColor = 0xFFFFFF00.toInt(), font = "Verdana", fixedTextSize = 30f, bold = true)
        settings.setStyle(style)
        settings.setAutoSelect("hi")

        // A new store reads what the last one wrote, as after a restart
        val again = DataStoreSubtitleSettings()
        assertEquals(style, again.style.value)
        assertEquals("hi", again.autoSelect.value)

        val file = savedWith("subs_auto_select")
        assertTrue("subtitle_settings" in file && "fixedTextSize" in file && "foregroundColor" in file, file)
    }

    @Test
    fun readsWhatTheAndroidAppSaved() {
        // As the Android app writes it: its font enum by name, sizes as floats
        DesktopAndroid.application.setKey(
            "subtitle_settings",
            mapOf(
                "foregroundColor" to -1, "backgroundColor" to 0, "windowColor" to 0, "edgeType" to 2,
                "edgeColor" to -16777216, "font" to "Netflix", "typefaceFilePath" to null, "elevation" to 30,
                "fixedTextSize" to 28.0, "edgeSize" to null, "removeCaptions" to true, "removeBloat" to true,
                "upperCase" to false, "bold" to false, "italic" to true, "backgroundRadius" to null, "alignment" to null,
            ),
        )
        DesktopAndroid.application.setKey("subs_auto_select", "None")

        val settings = DataStoreSubtitleSettings()
        val style = settings.style.value
        assertEquals("Netflix", style.font)
        assertEquals(SubtitleStyle.EDGE_DROP_SHADOW, style.edgeType)
        assertEquals(28f, style.fixedTextSize)
        assertEquals(30, style.elevation)
        assertTrue(style.italic && style.removeCaptions)
        assertNull(settings.autoSelect.value)

        // Writing keeps the fields the desktop doesn't use
        settings.setStyle(style.copy(bold = true))
        assertTrue(DataStoreSubtitleSettings().style.value.removeCaptions)
    }

    @Test
    fun noneIsSavedTheWayAndroidSavesIt() {
        val settings = DataStoreSubtitleSettings()
        settings.setAutoSelect(null)
        assertTrue("None" in savedWith("None"), "saved as the Android app saves it")
        assertNull(DataStoreSubtitleSettings().autoSelect.value)
    }

    @Test
    fun mapsTheStyleToMpvOptions() {
        val defaults = SubtitleStyle().mpvOptions().toMap()
        assertEquals("38", defaults["sub-font-size"])
        assertEquals("#FFFFFFFF", defaults["sub-color"])
        assertEquals("outline-and-shadow", defaults["sub-border-style"])
        assertEquals("2.0", defaults["sub-outline-size"])
        assertEquals("0.0", defaults["sub-shadow-offset"])
        assertEquals("sans-serif", defaults["sub-font"])
        // Also on subtitles with styles of their own, as on Android
        assertEquals("force", defaults["sub-ass-override"])

        val boxed = SubtitleStyle(
            backgroundColor = 0x80000000.toInt(),
            edgeType = SubtitleStyle.EDGE_DROP_SHADOW,
            font = "TimesNewRoman",
            fixedTextSize = 40f,
            italic = true,
        ).mpvOptions().toMap()
        assertEquals("background-box", boxed["sub-border-style"])
        assertEquals("#80000000", boxed["sub-back-color"])
        assertEquals("0.0", boxed["sub-outline-size"])
        assertEquals("2.0", boxed["sub-shadow-offset"])
        assertEquals("Times New Roman", boxed["sub-font"])
        assertEquals("60", boxed["sub-font-size"])
        assertEquals("yes", boxed["sub-italic"])
    }

    @Test
    fun findsTheLanguageOfNamesAndCodes() {
        assertEquals("en", SubtitleLanguages.tagOf("English"))
        assertEquals("en", SubtitleLanguages.tagOf("eng"))
        assertEquals("en", SubtitleLanguages.tagOf("English SDH"))
        assertEquals("hi", SubtitleLanguages.tagOf("hin"))
        assertNull(SubtitleLanguages.tagOf(null))
        assertEquals(listOf("en", "eng"), SubtitleLanguages.codesOf("en"))
    }
}
