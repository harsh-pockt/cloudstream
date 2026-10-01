package com.mihon.common.preference

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmPreferenceStoreTest {
    private enum class Quality { HD, SD, CAM }

    private val dir: Path = Files.createTempDirectory("cs-prefs")
    private val file: Path = dir.resolve("settings.json")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun valuesSurviveARestart() {
        JvmPreferenceStore(file).apply {
            getString("s").set("hello")
            getInt("i").set(42)
            getLong("l").set(9_000_000_000L)
            getFloat("f").set(1.5f)
            getBoolean("b").set(true)
            getStringSet("set").set(setOf("a", "b"))
            getEnumSet<Quality>("enum_set", emptySet()).set(setOf(Quality.HD, Quality.CAM))
            getObjectFromInt("obj_int", Quality.SD, { it.ordinal }, { Quality.entries[it] }).set(Quality.CAM)
        }

        val reopened = JvmPreferenceStore(file)
        assertEquals("hello", reopened.getString("s").get())
        assertEquals(42, reopened.getInt("i").get())
        assertEquals(9_000_000_000L, reopened.getLong("l").get())
        assertEquals(1.5f, reopened.getFloat("f").get())
        assertEquals(true, reopened.getBoolean("b").get())
        assertEquals(setOf("a", "b"), reopened.getStringSet("set").get())
        assertEquals(setOf(Quality.HD, Quality.CAM), reopened.getEnumSet<Quality>("enum_set", emptySet()).get())
        assertEquals(
            Quality.CAM,
            reopened.getObjectFromInt("obj_int", Quality.SD, { it.ordinal }, { Quality.entries[it] }).get()
        )
    }

    @Test
    fun missingFileGivesDefaultsAndIsNotCreatedUntilAWrite() {
        val store = JvmPreferenceStore(file)
        assertEquals(7, store.getInt("i", 7).get())
        assertFalse(store.getInt("i", 7).isSet())
        assertFalse(file.exists())

        store.getInt("i", 7).set(8)
        assertTrue(file.exists())
    }

    @Test
    fun corruptFileGivesDefaultsAndIsKeptForDebugging() {
        file.writeText("{ not json")
        val store = JvmPreferenceStore(file)
        assertEquals("default", store.getString("s", "default").get())
        assertTrue(dir.resolve("settings.json.corrupt").exists())

        store.getString("s").set("works")
        assertEquals("works", JvmPreferenceStore(file).getString("s").get())
    }

    @Test
    fun wrongTypeReadsAsDefault() {
        val store = JvmPreferenceStore(file)
        store.getString("key").set("not a number")
        assertEquals(5, store.getInt("key", 5).get())
        assertEquals(setOf("x"), store.getStringSet("key", setOf("x")).get())
    }

    @Test
    fun deleteRemovesTheValueFromDisk() {
        val store = JvmPreferenceStore(file)
        store.getBoolean("b").set(true)
        store.getBoolean("b").delete()

        assertFalse(store.getBoolean("b").isSet())
        assertFalse(JvmPreferenceStore(file).getBoolean("b").isSet())
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        val store = JvmPreferenceStore(file)
        repeat(5) { store.getInt("i").set(it) }
        assertEquals(listOf("settings.json"), dir.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun getAllReturnsPlainValues() {
        val store = JvmPreferenceStore(file)
        store.getString("s").set("text")
        store.getBoolean("b").set(true)
        store.getStringSet("set").set(setOf("a"))
        val all = store.getAll()
        assertEquals("text", all["s"])
        assertEquals(true, all["b"])
        assertEquals(setOf("a"), all["set"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changesEmitsTheCurrentValueThenEachUpdate() = runTest {
        val store = JvmPreferenceStore(file)
        val pref = store.getInt("i", 0)
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pref.changes().toList(seen) }

        pref.set(1)
        store.getInt("other").set(99) // Other keys must not emit
        pref.set(2)

        assertEquals(listOf(0, 1, 2), seen)
    }
}
