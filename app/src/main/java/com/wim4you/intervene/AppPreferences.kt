package com.wim4you.intervene

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "intervene_app_prefs"
    private const val KEY_PATROL_ALERT_SOUND = "patrol_alert_sound_enabled"
    private const val KEY_CHAT_RINGING_SOUND = "chat_ringing_sound_enabled"
    private const val KEY_READ_ALOUD = "read_aloud_enabled"
    private const val KEY_DISTRESS_SIREN_SOUND = "distress_siren_sound_enabled"

    fun isPatrolAlertSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PATROL_ALERT_SOUND, true)
    }

    fun setPatrolAlertSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PATROL_ALERT_SOUND, enabled).apply()
    }

    fun isChatRingingSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CHAT_RINGING_SOUND, true)
    }

    fun setChatRingingSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHAT_RINGING_SOUND, enabled).apply()
    }

    fun isReadAloudEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_READ_ALOUD, true)
    }

    fun setReadAloudEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_ALOUD, enabled).apply()
    }

    fun isDistressSirenSoundEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DISTRESS_SIREN_SOUND, true)
    }

    fun setDistressSirenSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISTRESS_SIREN_SOUND, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
