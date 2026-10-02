package com.lagradost.cloudstream4.search

import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream4.android.DesktopAndroid
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataStoreSearchHistoryTest {
    private val dir = Files.createTempDirectory("cs-search")

    init {
        DesktopAndroid.init(dir)
    }

    @AfterTest
    fun cleanup() {
        DesktopAndroid.toastHandler = null
        dir.toFile().deleteRecursively()
    }

    @Test
    fun savesInTheAndroidAppsKeyAndReadsBack() {
        var now = 0L
        val history = DataStoreSearchHistory(clock = { ++now })
        history.add("dune")
        history.add("alien")
        history.add("d")

        // A new store reads what the last one wrote, as after a restart
        val again = DataStoreSearchHistory()
        assertEquals(listOf("alien", "dune"), again.entries().map { it.query })
        assertEquals("dune".hashCode().toString(), again.entries().last().key)

        val file = dir.resolve("shared_prefs/rebuild_preference.json")
        val key = "0/search_history/${"dune".hashCode()}"
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !(Files.exists(file) && key in file.readText())) Thread.sleep(50)
        assertTrue(key in file.readText(), "saved under the Android app's key")

        again.remove("dune".hashCode().toString())
        assertEquals(listOf("alien"), again.entries().map { it.query })
        again.clear()
        assertTrue(again.entries().isEmpty())
    }

    @Test
    fun extensionToastsReachTheApp() {
        val shown = mutableListOf<String>()
        DesktopAndroid.toastHandler = { shown += it }

        Toast.makeText(DesktopAndroid.application, "Logged in", Toast.LENGTH_LONG).show()
        CommonActivity.showToast("Cookies saved")
        // Android shows nothing for an empty toast
        Toast.makeText(DesktopAndroid.application, "", Toast.LENGTH_SHORT).show()

        assertEquals(listOf("Logged in", "Cookies saved"), shown)
    }
}
