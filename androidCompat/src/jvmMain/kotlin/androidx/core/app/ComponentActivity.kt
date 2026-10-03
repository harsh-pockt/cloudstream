package androidx.core.app

import android.app.Activity

/**
 * Real desktop classes for the activity chain Android extensions cast the app's activity to, often
 * `context as AppCompatActivity` in their load. Stubs generated inside the extension would be other
 * classes than the app's activity, so the cast failed. Members desktop lacks are redirected by
 * MissingMembers like on any desktop stand-in.
 */
open class ComponentActivity : Activity()
