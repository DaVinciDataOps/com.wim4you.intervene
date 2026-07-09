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

object FirebaseUtils {

    private const val TAG = "FirebaseUtils"
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        FirebaseApp.initializeApp(context)
        installAppCheck()
        initScope.launch {
            try {
                FirebaseAuthManager.ensureSignedIn()
            } catch (exception: Exception) {
                Log.e(TAG, "Anonymous sign-in failed during initialization", exception)
            }
        }
    }

    suspend fun ensureReady(): String {
        return FirebaseAuthManager.ensureSignedIn()
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
