package com.wim4you.intervene.security

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.SecureLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ensures the current user's Firebase patrol/distress entries match local app mode
 * and marks expired location records inactive.
 */
object FirebaseDataRetention {

    private const val TAG = "FirebaseDataRetention"

    suspend fun reconcileOwnEntries() {
        try {
            val uid = FirebaseAuthManager.ensureSignedIn()
            val database = FirebaseDatabase.getInstance().reference
            val now = System.currentTimeMillis()
            val expiryCutoff = now - AppModeController.LOCATION_DATA_EXPIRY_MS

            if (!AppModeController.isPatrolling) {
                markInactive(database, "patrols", uid)
            } else {
                deactivateIfExpired(database, "patrols", uid, expiryCutoff)
            }

            if (!AppModeController.isDistressActive) {
                markInactive(database, "distress", uid)
            } else {
                deactivateIfExpired(database, "distress", uid, expiryCutoff)
            }
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to reconcile Firebase entries", exception)
        }
    }

    private suspend fun deactivateIfExpired(
        database: com.google.firebase.database.DatabaseReference,
        path: String,
        uid: String,
        expiryCutoff: Long,
    ) {
        val snapshot = database.child(path).child(uid).getOnce()
        if (!snapshot.exists()) return
        val timestamp = snapshot.child("time").getValue(Long::class.java)
            ?: snapshot.child("startTime").getValue(Long::class.java)
        if (timestamp != null && timestamp < expiryCutoff) {
            markInactive(database, path, uid)
            SecureLog.i(TAG, "Marked expired $path entry inactive for $uid")
        }
    }

    private suspend fun markInactive(
        database: com.google.firebase.database.DatabaseReference,
        path: String,
        uid: String,
    ) {
        if (path == "distress" && AppModeController.isDistressActive) return
        if (path == "patrols" && AppModeController.isPatrolling) return
        val snapshot = database.child(path).child(uid).getOnce()
        if (!snapshot.exists()) return
        if (path == "distress" && AppModeController.isDistressActive) return
        if (path == "patrols" && AppModeController.isPatrolling) return
        database.child(path).child(uid).child("active").setValueOnce(false)
    }

    private suspend fun com.google.firebase.database.DatabaseReference.getOnce(): DataSnapshot =
        suspendCancellableCoroutine { continuation ->
            addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    continuation.resume(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resumeWithException(error.toException())
                }
            })
        }

    private suspend fun com.google.firebase.database.DatabaseReference.setValueOnce(value: Any?) {
        suspendCancellableCoroutine { continuation ->
            setValue(value)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
