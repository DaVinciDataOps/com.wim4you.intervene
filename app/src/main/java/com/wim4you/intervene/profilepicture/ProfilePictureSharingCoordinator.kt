package com.wim4you.intervene.profilepicture

import android.content.Context

/**
 * Public entry point for profile picture capture, storage, and 2 km proximity sharing.
 */
object ProfilePictureSharingCoordinator {

    fun isSharingEnabled(context: Context): Boolean {
        return ProfilePicturePreferences.isSharingEnabled(context)
    }

    fun setSharingEnabled(context: Context, enabled: Boolean) {
        ProfilePicturePreferences.setSharingEnabled(context, enabled)
    }

    fun hasLocalPicture(context: Context): Boolean {
        return ProfilePictureLocalStore.hasPicture(context)
    }

    /**
     * Returns the URL to publish to Firebase when sharing is enabled, or null otherwise.
     */
    fun getPublishedUrl(context: Context): String? {
        if (!isSharingEnabled(context)) return null
        if (!hasLocalPicture(context)) return null
        return ProfilePicturePreferences.getRemoteUrl(context)
    }

    suspend fun onPictureChanged(context: Context): Result<Unit> {
        return runCatching {
            if (isSharingEnabled(context)) {
                ProfilePictureFirebaseSync.upload(context)
            } else {
                ProfilePicturePreferences.setRemoteUrl(context, null)
            }
        }
    }

    suspend fun onSharingToggled(context: Context, enabled: Boolean) {
        setSharingEnabled(context, enabled)
        if (!enabled) {
            ProfilePictureFirebaseSync.deleteRemote(context)
            return
        }
        if (hasLocalPicture(context)) {
            ProfilePictureFirebaseSync.upload(context)
        }
    }

    suspend fun removePicture(context: Context) {
        ProfilePictureFirebaseSync.deleteRemote(context)
        ProfilePictureLocalStore.deleteLocal(context)
    }
}
