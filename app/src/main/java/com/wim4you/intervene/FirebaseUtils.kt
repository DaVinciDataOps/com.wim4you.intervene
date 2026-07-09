package com.wim4you.intervene

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object FirebaseUtils {

    private const val TAG = "FirebaseUtils"
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        FirebaseApp.initializeApp(context)
        installAppCheck()
        initScope.launch {
            try {
                awaitAppCheckToken()
                FirebaseAuthManager.ensureSignedIn()
            } catch (exception: Exception) {
                Log.e(TAG, "Anonymous sign-in failed during initialization", exception)
            }
        }
    }

    suspend fun ensureReady(): String {
        awaitAppCheckToken()
        return FirebaseAuthManager.ensureSignedIn()
    }

    private suspend fun awaitAppCheckToken() {
        suspendCancellableCoroutine { continuation ->
            FirebaseAppCheck.getInstance()
                .getAppCheckToken(false)
                .addOnSuccessListener {
                    SecureLog.d(TAG, "App Check token ready")
                    continuation.resume(Unit)
                }
                .addOnFailureListener { error ->
                    SecureLog.e(
                        TAG,
                        "App Check token failed. In debug builds, register the debug token from Logcat in Firebase Console → App Check.",
                        error,
                    )
                    continuation.resume(Unit)
                }
        }
    }

    private fun installAppCheck() {
        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
        } else {
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance(),
            )
        }
    }
}
