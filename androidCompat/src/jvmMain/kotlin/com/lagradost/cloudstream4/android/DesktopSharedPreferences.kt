package com.lagradost.cloudstream4.android

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * SharedPreferences kept in a JSON file. Each value is stored with its type, because Android's
 * getters throw ClassCastException for the wrong type and extensions rely on Int staying Int.
 */
internal class DesktopSharedPreferences(private val file: Path) : SharedPreferences {
    private val values: MutableMap<String, Any> = read()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = synchronized(values) { values.toMap() }

    override fun getString(key: String?, defValue: String?): String? = get(key) as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (get(key) as Set<String>?)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = get(key) as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = get(key) as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = get(key) as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = get(key) as Boolean? ?: defValue
    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

    private fun get(key: String?): Any? = synchronized(values) { values[key] }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::addIfAbsent)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        private fun put(key: String?, value: Any?) = apply { if (key != null) synchronized(changes) { changes[key] = value } }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            val changed = applyToMemory()
            return runCatching { write() }.onFailure { println("ERROR SharedPreferences: Could not save $file: $it") }.isSuccess
                .also { notify(changed) }
        }

        override fun apply() {
            val changed = applyToMemory()
            writer.execute { runCatching { write() }.onFailure { println("ERROR SharedPreferences: Could not save $file: $it") } }
            notify(changed)
        }

        private fun applyToMemory(): List<String> = synchronized(values) {
            val changed = mutableListOf<String>()
            if (clear) {
                changed += values.keys
                values.clear()
            }
            synchronized(changes) {
                for ((key, value) in changes) {
                    if (value == null) values.remove(key) else values[key] = value
                    changed += key
                }
            }
            changed.distinct()
        }

        private fun notify(changed: List<String>) {
            for (key in changed) for (listener in listeners) {
                runCatching { listener.onSharedPreferenceChanged(this@DesktopSharedPreferences, key) }
            }
        }
    }

    private fun write() = synchronized(file) {
        val snapshot = synchronized(values) { values.toMap() }
        val json = JsonObject(snapshot.mapValues { (_, value) -> encode(value) })
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private fun read(): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        if (!file.exists()) return map
        runCatching {
            for ((key, element) in Json.parseToJsonElement(file.readText()).jsonObject) {
                decode(element.jsonObject)?.let { map[key] = it }
            }
        }.onFailure { println("ERROR SharedPreferences: Could not read $file: $it") }
        return map
    }

    private companion object {
        /** All files are written from one thread, in order, like Android's apply() */
        val writer = Executors.newSingleThreadExecutor { Thread(it, "SharedPreferences writer").apply { isDaemon = true } }

        fun encode(value: Any): JsonObject = when (value) {
            is String -> typed("string", JsonPrimitive(value))
            is Int -> typed("int", JsonPrimitive(value))
            is Long -> typed("long", JsonPrimitive(value))
            is Float -> typed("float", JsonPrimitive(value))
            is Boolean -> typed("boolean", JsonPrimitive(value))
            is Set<*> -> typed("set", JsonArray(value.map { JsonPrimitive(it as String) }))
            else -> typed("string", JsonPrimitive(value.toString()))
        }

        fun typed(type: String, value: kotlinx.serialization.json.JsonElement) =
            JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

        fun decode(obj: JsonObject): Any? {
            val value = obj["value"] ?: return null
            return when (obj["type"]?.jsonPrimitive?.content) {
                "string" -> value.jsonPrimitive.content
                "int" -> value.jsonPrimitive.content.toInt()
                "long" -> value.jsonPrimitive.content.toLong()
                "float" -> value.jsonPrimitive.content.toFloat()
                "boolean" -> value.jsonPrimitive.content.toBoolean()
                "set" -> value.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> null
            }
        }
    }
}
