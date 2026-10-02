package androidx.preference

import android.content.Context
import android.content.SharedPreferences

/** Only the default preferences lookup, which extensions call outside their settings screens too */
open class PreferenceManager {
    companion object {
        @JvmStatic
        fun getDefaultSharedPreferencesName(context: Context?): String = (context?.getPackageName() ?: "app") + "_preferences"

        @JvmStatic
        fun getDefaultSharedPreferences(context: Context?): SharedPreferences =
            (context ?: com.lagradost.cloudstream4.android.DesktopAndroid.application)
                .getSharedPreferences(getDefaultSharedPreferencesName(context), Context.MODE_PRIVATE)
    }
}
