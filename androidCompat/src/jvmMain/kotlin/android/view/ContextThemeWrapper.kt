package android.view

import android.content.Context
import android.content.ContextWrapper

open class ContextThemeWrapper(base: Context?) : ContextWrapper(base) {
    /** Generated stub subclasses call the no-argument constructor */
    constructor() : this(null)

    constructor(base: Context?, themeResId: Int) : this(base)
}
