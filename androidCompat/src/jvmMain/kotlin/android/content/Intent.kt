package android.content

import android.net.Uri
import android.os.Bundle

/** Android's Intent as a plain holder. Context.startActivity opens ACTION_VIEW web links and ignores the rest */
open class Intent() {
    private var action: String? = null
    private var data: Uri? = null
    private var type: String? = null
    private var flags = 0
    private var component: ComponentName? = null
    private var packageName: String? = null
    private val extras = Bundle()

    constructor(action: String?) : this() {
        this.action = action
    }

    constructor(action: String?, uri: Uri?) : this(action) {
        data = uri
    }

    constructor(packageContext: Context?, cls: Class<*>?) : this() {
        component = cls?.let { ComponentName(packageContext?.getPackageName(), it.name) }
    }

    constructor(other: Intent?) : this() {
        if (other != null) {
            action = other.action
            data = other.data
            type = other.type
            flags = other.flags
            component = other.component
            packageName = other.packageName
            extras.putAll(other.extras)
        }
    }

    open fun getAction(): String? = action
    open fun setAction(action: String?): Intent = apply { this.action = action }
    open fun getData(): Uri? = data
    open fun setData(data: Uri?): Intent = apply { this.data = data }
    open fun getType(): String? = type
    open fun setType(type: String?): Intent = apply { this.type = type }
    open fun setDataAndType(data: Uri?, type: String?): Intent = apply { this.data = data; this.type = type }
    open fun getFlags(): Int = flags
    open fun setFlags(flags: Int): Intent = apply { this.flags = flags }
    open fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }
    open fun getComponent(): ComponentName? = component
    open fun setComponent(component: ComponentName?): Intent = apply { this.component = component }
    open fun getPackage(): String? = packageName
    open fun setPackage(packageName: String?): Intent = apply { this.packageName = packageName }
    open fun setClassName(packageName: String?, className: String?): Intent =
        apply { component = ComponentName(packageName, className) }

    open fun getExtras(): Bundle? = extras
    open fun putExtras(extras: Bundle?): Intent = apply { extras?.let(this.extras::putAll) }
    open fun hasExtra(name: String?): Boolean = extras.containsKey(name)
    open fun putExtra(name: String?, value: String?): Intent = apply { extras.putString(name, value) }
    open fun putExtra(name: String?, value: Int): Intent = apply { extras.putInt(name, value) }
    open fun putExtra(name: String?, value: Long): Intent = apply { extras.putLong(name, value) }
    open fun putExtra(name: String?, value: Boolean): Intent = apply { extras.putBoolean(name, value) }
    open fun putExtra(name: String?, value: Float): Intent = apply { extras.putFloat(name, value) }
    open fun putExtra(name: String?, value: Double): Intent = apply { extras.putDouble(name, value) }
    open fun putExtra(name: String?, value: Bundle?): Intent = apply { extras.putBundle(name, value) }
    open fun putExtra(name: String?, value: java.io.Serializable?): Intent = apply { extras.putSerializable(name, value) }
    open fun getStringExtra(name: String?): String? = extras.getString(name)
    open fun getIntExtra(name: String?, defaultValue: Int): Int = extras.getInt(name, defaultValue)
    open fun getLongExtra(name: String?, defaultValue: Long): Long = extras.getLong(name, defaultValue)
    open fun getBooleanExtra(name: String?, defaultValue: Boolean): Boolean = extras.getBoolean(name, defaultValue)

    override fun toString(): String = "Intent { act=$action dat=$data cmp=$component }"

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val EXTRA_INTENT = "android.intent.extra.INTENT"
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000

        /** Extensions use this to restart the app after a settings change, which desktop ignores */
        @JvmStatic
        fun makeRestartActivityTask(mainActivity: ComponentName?): Intent = Intent(ACTION_MAIN).setComponent(mainActivity)

        @JvmStatic
        fun createChooser(target: Intent?, title: CharSequence?): Intent = Intent(target)
    }
}

open class ComponentName(private val pkg: String?, private val cls: String?) {
    constructor(pkg: Context?, cls: Class<*>?) : this(pkg?.getPackageName(), cls?.name)

    open fun getPackageName(): String? = pkg
    open fun getClassName(): String? = cls
    override fun toString(): String = "ComponentName($pkg/$cls)"
}
