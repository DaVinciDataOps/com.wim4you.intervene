package com.wim4you.intervene.profilepicture

import android.content.Context

internal object ProfilePicturePreferences {
    private const val PREFS_NAME = "profile_picture_prefs"
    private const val KEY_SHARING_ENABLED = "sharing_enabled"
    private const val KEY_LOCAL_FILENAME = "local_filename"
    private const val KEY_REMOTE_URL = "remote_url"

    fun isSharingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHARING_ENABLED, false)
    }

    fun setSharingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHARING_ENABLED, enabled).apply()
    }

    fun getLocalFilename(context: Context): String? {
        return prefs(context).getString(KEY_LOCAL_FILENAME, null)
    }

    fun setLocalFilename(context: Context, filename: String?) {
        prefs(context).edit().putString(KEY_LOCAL_FILENAME, filename).apply()
    }

    fun getRemoteUrl(context: Context): String? {
        return prefs(context).getString(KEY_REMOTE_URL, null)
    }

    fun setRemoteUrl(context: Context, url: String?) {
        prefs(context).edit().putString(KEY_REMOTE_URL, url).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
