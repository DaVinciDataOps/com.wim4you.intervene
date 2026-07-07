package com.wim4you.intervene

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemePreferences {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "theme_mode"

    enum class Mode {
        SYSTEM,
        LIGHT,
        DARK,
    }

    fun getMode(context: Context): Mode {
        val value = prefs(context).getString(KEY_MODE, Mode.SYSTEM.name)
        return Mode.entries.find { it.name == value } ?: Mode.SYSTEM
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        applyTheme(mode)
    }

    fun applySavedTheme(context: Context) {
        applyTheme(getMode(context))
    }

    fun isDarkModeActive(context: Context): Boolean {
        return when (getMode(context)) {
            Mode.DARK -> true
            Mode.LIGHT -> false
            Mode.SYSTEM -> {
                val nightModeFlags = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun applyTheme(mode: Mode) {
        val nightMode = when (mode) {
            Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
