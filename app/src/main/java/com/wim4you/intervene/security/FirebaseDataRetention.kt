package com.wim4you.intervene.security

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.distress.DistressFirebaseWriter
import com.wim4you.intervene.location.PatrolFirebaseWriter
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
            val database = FirebaseDatabaseProvider.reference()
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
            val failureKey = FirebaseAuthManager.authFailureKey(exception)
            if (failureKey == "auth_not_configured") {
                SecureLog.w(TAG, "Reconciliation skipped: Firebase Auth not configured in console.")
            } else {
                SecureLog.e(TAG, "Failed to reconcile Firebase entries: ${exception.message}")
            }
        }
    }

    private suspend fun deactivateIfExpired(
        database: DatabaseReference,
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
        database: DatabaseReference,
        path: String,
        uid: String,
    ) {
        if (path == "distress" && AppModeController.isDistressActive) return
        if (path == "patrols" && AppModeController.isPatrolling) return
        val snapshot = database.child(path).child(uid).getOnce()
        if (!snapshot.exists()) return
        if (path == "distress") {
            DistressFirebaseWriter.markDistressInactive(uid)
        } else {
            PatrolFirebaseWriter.markPatrolInactive(uid)
        }
    }

    private suspend fun DatabaseReference.getOnce(): DataSnapshot =
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

    private suspend fun DatabaseReference.setValueOnce(value: Any?) {
        suspendCancellableCoroutine { continuation ->
            setValue(value)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
