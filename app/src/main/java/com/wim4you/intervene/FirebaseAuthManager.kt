package com.wim4you.intervene

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FirebaseAuthManager {

    private const val TAG = "FirebaseAuthManager"
    private const val MAX_ATTEMPTS = 3

    private val signInMutex = Mutex()

    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    suspend fun ensureSignedIn(): String {
        currentUid()?.let { return it }

        return signInMutex.withLock {
            currentUid()?.let { return it }
            signInWithRetries()
        }
    }

    fun authFailureKey(exception: Exception): String {
        if (exception is FirebaseAuthException) {
            return when (exception.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" -> "auth_anonymous_disabled"
                "ERROR_NETWORK_REQUEST_FAILED" -> "auth_network"
                "ERROR_TOO_MANY_REQUESTS" -> "auth_rate_limited"
                else -> "auth_failed"
            }
        }
        val message = exception.message.orEmpty().lowercase()
        if (message.contains("network") || message.contains("unable to resolve host")) {
            return "auth_network"
        }
        return "auth_failed"
    }

    private suspend fun signInWithRetries(): String {
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
                        SecureLog.i(TAG, "Anonymous sign-in succeeded")
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
