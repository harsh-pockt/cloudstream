package com.mihon.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Desktop implementation of PreferenceStore, keeping every preference in a single JSON file.
 *
 * Every write is saved right away by writing a temporary file and moving it over the old one,
 * so a crash never leaves a half written file behind. A missing or unreadable file means defaults.
 */
class JvmPreferenceStore(private val file: Path) : PreferenceStore {
    private val lock = Any()
    private val values: MutableMap<String, JsonElement> = load(file)
    private val keyFlow = MutableSharedFlow<String?>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    internal fun read(key: String): JsonElement? = synchronized(lock) { values[key] }

    internal fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    /** Writes or, with a null value, removes a key and saves the file */
    internal fun write(key: String, value: JsonElement?) {
        synchronized(lock) {
            if (value == null) values.remove(key) else values[key] = value
            save()
        }
        keyFlow.tryEmit(key)
    }

    private fun save() {
        file.parent?.createDirectories()
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        tmp.writeText(JsonObject(values.toMap()).toString())
        Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private fun <T> preference(
        key: String,
        defaultValue: T,
        decode: (JsonElement) -> T?,
        encode: (T) -> JsonElement,
    ): PreferenceData<T> = JvmPreference(this, keyFlow, key, defaultValue, decode, encode)

    override fun getString(key: String, defaultValue: String): PreferenceData<String> =
        preference(key, defaultValue, { it.stringOrNull() }, ::JsonPrimitive)

    override fun getLong(key: String, defaultValue: Long): PreferenceData<Long> =
        preference(key, defaultValue, { it.jsonPrimitive.longOrNull }, ::JsonPrimitive)

    override fun getInt(key: String, defaultValue: Int): PreferenceData<Int> =
        preference(key, defaultValue, { it.jsonPrimitive.intOrNull }, ::JsonPrimitive)

    override fun getFloat(key: String, defaultValue: Float): PreferenceData<Float> =
        preference(key, defaultValue, { it.jsonPrimitive.floatOrNull }, ::JsonPrimitive)

    override fun getBoolean(key: String, defaultValue: Boolean): PreferenceData<Boolean> =
        preference(key, defaultValue, { it.jsonPrimitive.booleanOrNull }, ::JsonPrimitive)

    override fun getStringSet(key: String, defaultValue: Set<String>): PreferenceData<Set<String>> =
        preference(key, defaultValue, { it.stringSet() }, { it.toJsonArray() })

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T> = preference(
        key,
        defaultValue,
        { it.stringOrNull()?.let(deserializer) },
        { JsonPrimitive(serializer(it)) },
    )

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T> = preference(
        key,
        defaultValue,
        { it.jsonPrimitive.intOrNull?.let(deserializer) },
        { JsonPrimitive(serializer(it)) },
    )

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>> = preference(
        key,
        defaultValue,
        { it.stringSet().mapNotNull(deserializer).toSet() },
        { set -> set.map(serializer).toSet().toJsonArray() },
    )

    override fun getAll(): Map<String, *> = synchronized(lock) {
        values.mapValues { (_, value) ->
            when (value) {
                is JsonArray -> value.stringSet()
                is JsonPrimitive -> value.stringOrNull()
                    ?: value.booleanOrNull
                    ?: value.longOrNull
                    ?: value.doubleOrNull
                else -> null
            }
        }
    }

    private companion object {
        fun load(file: Path): MutableMap<String, JsonElement> {
            if (!file.exists()) return mutableMapOf()
            return try {
                Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
            } catch (t: Throwable) {
                // Keep the unreadable file for debugging instead of silently overwriting it on the next write
                println("ERROR JvmPreferenceStore: Could not read $file, using defaults: $t")
                runCatching { Files.copy(file, file.resolveSibling("${file.fileName}.corrupt"), REPLACE_EXISTING) }
                mutableMapOf()
            }
        }

        fun JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonElement.stringSet(): Set<String> =
            jsonArray.mapNotNull { it.stringOrNull() }.toSet()

        fun Set<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
    }
}

class JvmPreference<T>(
    private val store: JvmPreferenceStore,
    private val keyFlow: Flow<String?>,
    private val key: String,
    private val defaultValue: T,
    private val decode: (JsonElement) -> T?,
    private val encode: (T) -> JsonElement,
) : PreferenceData<T> {
    override fun key(): String = key

    /** A value of the wrong type, or one the deserializer rejects, reads as the default */
    override fun get(): T {
        val stored = store.read(key) ?: return defaultValue
        return runCatching { decode(stored) }.getOrNull() ?: defaultValue
    }

    override fun set(value: T) {
        store.write(key, encode(value))
    }

    override fun isSet(): Boolean = store.contains(key)

    override fun delete() {
        store.write(key, null)
    }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> {
        return keyFlow
            .filter { it == key || it == null }
            .onStart { emit("ignition") }
            .map { get() }
            .conflate()
    }

    override fun stateIn(scope: CoroutineScope): StateFlow<T> {
        return changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}
