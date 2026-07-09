package com.wim4you.intervene

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "intervene_app_prefs"
    private const val KEY_PATROL_ALERT_SOUND = "patrol_alert_sound_enabled"

    fun isPatrolAlertSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PATROL_ALERT_SOUND, true)
    }

    fun setPatrolAlertSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PATROL_ALERT_SOUND, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
