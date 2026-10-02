package android.os

import java.io.Serializable

/** Android's Bundle as a map. Extensions use it for fragment arguments and intent extras */
open class Bundle() {
    private val map = LinkedHashMap<String?, Any?>()

    constructor(capacity: Int) : this()

    constructor(other: Bundle?) : this() {
        other?.let(::putAll)
    }

    open fun size(): Int = map.size
    open fun isEmpty(): Boolean = map.isEmpty()
    open fun clear() = map.clear()
    open fun containsKey(key: String?): Boolean = map.containsKey(key)
    open fun remove(key: String?) {
        map.remove(key)
    }

    open fun keySet(): Set<String?> = map.keys
    open fun putAll(bundle: Bundle?) {
        bundle?.let { map.putAll(it.map) }
    }

    open fun get(key: String?): Any? = map[key]

    private inline fun <reified T> typed(key: String?): T? = map[key] as? T

    open fun putString(key: String?, value: String?) = map.set(key, value)
    open fun getString(key: String?): String? = typed(key)
    open fun getString(key: String?, defaultValue: String?): String? = typed<String>(key) ?: defaultValue
    open fun putCharSequence(key: String?, value: CharSequence?) = map.set(key, value)
    open fun getCharSequence(key: String?): CharSequence? = typed(key)
    open fun putInt(key: String?, value: Int) = map.set(key, value)
    open fun getInt(key: String?): Int = getInt(key, 0)
    open fun getInt(key: String?, defaultValue: Int): Int = typed<Int>(key) ?: defaultValue
    open fun putLong(key: String?, value: Long) = map.set(key, value)
    open fun getLong(key: String?): Long = getLong(key, 0L)
    open fun getLong(key: String?, defaultValue: Long): Long = typed<Long>(key) ?: defaultValue
    open fun putFloat(key: String?, value: Float) = map.set(key, value)
    open fun getFloat(key: String?): Float = getFloat(key, 0f)
    open fun getFloat(key: String?, defaultValue: Float): Float = typed<Float>(key) ?: defaultValue
    open fun putDouble(key: String?, value: Double) = map.set(key, value)
    open fun getDouble(key: String?): Double = getDouble(key, 0.0)
    open fun getDouble(key: String?, defaultValue: Double): Double = typed<Double>(key) ?: defaultValue
    open fun putBoolean(key: String?, value: Boolean) = map.set(key, value)
    open fun getBoolean(key: String?): Boolean = getBoolean(key, false)
    open fun getBoolean(key: String?, defaultValue: Boolean): Boolean = typed<Boolean>(key) ?: defaultValue
    open fun putBundle(key: String?, value: Bundle?) = map.set(key, value)
    open fun getBundle(key: String?): Bundle? = typed(key)
    open fun putSerializable(key: String?, value: Serializable?) = map.set(key, value)
    open fun getSerializable(key: String?): Serializable? = typed(key)
    open fun putStringArrayList(key: String?, value: ArrayList<String>?) = map.set(key, value)

    @Suppress("UNCHECKED_CAST")
    open fun getStringArrayList(key: String?): ArrayList<String>? = map[key] as? ArrayList<String>
    open fun putStringArray(key: String?, value: Array<String>?) = map.set(key, value)

    @Suppress("UNCHECKED_CAST")
    open fun getStringArray(key: String?): Array<String>? = map[key] as? Array<String>

    override fun toString(): String = "Bundle$map"

    companion object {
        @JvmField
        val EMPTY = Bundle()
    }
}
