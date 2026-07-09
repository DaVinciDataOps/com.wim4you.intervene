package com.wim4you.intervene

import com.google.firebase.auth.FirebaseAuth
import com.wim4you.intervene.SecureLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FirebaseAuthManager {

    private const val TAG = "FirebaseAuthManager"
    private const val MAX_ATTEMPTS = 3

    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    suspend fun ensureSignedIn(): String {
        val existingUid = currentUid()
        if (existingUid != null) {
            return existingUid
        }

        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return signInAnonymouslyOnce()
            } catch (exception: Exception) {
                lastError = exception
                SecureLog.e(TAG, "Anonymous sign-in attempt ${attempt + 1} failed", exception)
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(500L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Anonymous sign-in failed")
    }

    private suspend fun signInAnonymouslyOnce(): String =
        suspendCancellableCoroutine { continuation ->
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
