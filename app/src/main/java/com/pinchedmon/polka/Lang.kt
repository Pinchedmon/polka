package com.pinchedmon.polka

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language without AppCompat: Android 13+ uses the system per-app language
 * (also visible in system settings), older versions keep the choice in prefs and wrap the context.
 * An empty tag means "follow the system".
 */
object Lang {
    private const val PREF = "lang"

    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore('-')
        } else {
            prefs(ctx).getString(PREF, "") ?: ""
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(PREF, tag).apply()
            activity.recreate()
        }
    }

    /** For Activity.attachBaseContext on Android 12 and older. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(PREF, "") ?: ""
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("polka", Context.MODE_PRIVATE)
}
