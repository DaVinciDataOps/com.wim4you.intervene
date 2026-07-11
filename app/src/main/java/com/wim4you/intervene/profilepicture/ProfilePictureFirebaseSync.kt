package com.wim4you.intervene.profilepicture

import android.content.Context
import android.graphics.BitmapFactory
import com.google.firebase.storage.FirebaseStorage
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.SecureLog
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

internal object ProfilePictureFirebaseSync {
    private const val TAG = "ProfilePictureFirebaseSync"
    private const val STORAGE_PATH = "profile_pictures"

    suspend fun upload(context: Context): String? {
        val filename = ProfilePicturePreferences.getLocalFilename(context) ?: return null
        val file = java.io.File(
            java.io.File(context.filesDir, "profile_pictures"),
            filename,
        )
        if (!file.exists()) return null

        val uid = FirebaseAuthManager.ensureSignedIn()
        val storageRef = FirebaseStorage.getInstance()
            .reference
            .child(STORAGE_PATH)
            .child("$uid.jpg")

        val bytes = file.readBytes()
        storageRef.putBytes(bytes).await()
        val downloadUrl = storageRef.downloadUrl.await().toString()
        ProfilePicturePreferences.setRemoteUrl(context, downloadUrl)
        SecureLog.i(TAG, "Profile picture uploaded for $uid")
        return downloadUrl
    }

    suspend fun deleteRemote(context: Context) {
        val uid = try {
            FirebaseAuthManager.ensureSignedIn()
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Could not authenticate before deleting profile picture", exception)
            ProfilePicturePreferences.setRemoteUrl(context, null)
            return
        }

        try {
            FirebaseStorage.getInstance()
                .reference
                .child(STORAGE_PATH)
                .child("$uid.jpg")
                .delete()
                .await()
            SecureLog.i(TAG, "Profile picture deleted for $uid")
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Could not delete remote profile picture", exception)
        } finally {
            ProfilePicturePreferences.setRemoteUrl(context, null)
        }
    }
}
