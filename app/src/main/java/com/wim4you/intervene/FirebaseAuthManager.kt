package com.wim4you.intervene

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FirebaseAuthManager {

    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    suspend fun ensureSignedIn(): String {
        val existingUid = currentUid()
        if (existingUid != null) {
            return existingUid
        }

        return suspendCancellableCoroutine { continuation ->
            FirebaseAuth.getInstance()
                .signInAnonymously()
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (uid != null) {
                        continuation.resume(uid)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Anonymous sign-in succeeded without a user id")
                        )
                    }
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }
    }
}
