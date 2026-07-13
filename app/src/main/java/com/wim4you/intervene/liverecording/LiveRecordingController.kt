package com.wim4you.intervene.liverecording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.Constants
import com.wim4you.intervene.helpers.ServiceUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owns live recording lifecycle during guided trips.
 * Keeps recording concerns separate from [AppModeController].
 */
object LiveRecordingController {
    private const val TAG = "LiveRecordingController"
    private const val PREFS_NAME = "live_recording_state"
    private const val KEY_IS_RECORDING = "is_recording"

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    var isRecording: Boolean = false
        private set

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
    }

    fun canStartRecording(): Boolean = AppModeController.isGuidedTrip && !isRecording

    fun ensureForegroundService(context: Context): Boolean {
        if (!AppModeController.isGuidedTrip) return false
        val appContext = context.applicationContext
        val attributedContext = appContext.createAttributionContext(Constants.LIVE_RECORDING_SERVICE_CONTEXT_TAG)
        val intent = Intent(attributedContext, LiveRecordingService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                attributedContext.startForegroundService(intent)
            } else {
                attributedContext.startService(intent)
            }
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start live recording service", exception)
            false
        }
    }

    fun stopRecording(context: Context) {
        _stopRequests.tryEmit(Unit)
        val appContext = context.applicationContext
        val attributedContext = appContext.createAttributionContext(Constants.LIVE_RECORDING_SERVICE_CONTEXT_TAG)
        attributedContext.stopService(Intent(attributedContext, LiveRecordingService::class.java))
        isRecording = false
        persistState(appContext)
    }

    fun onRecordingStopped() {
        isRecording = false
        val context = appContext ?: return
        persistState(context)
    }

    fun onRecordingStarted() {
        isRecording = true
        val context = appContext ?: return
        persistState(context)
    }

    fun reconcileService(context: Context) {
        val appContext = context.applicationContext
        if (!AppModeController.isGuidedTrip) {
            if (isRecording) {
                stopRecording(appContext)
            }
            return
        }
        if (isRecording && !ServiceUtils.isServiceRunning(appContext, LiveRecordingService::class.java)) {
            Log.i(TAG, "Clearing stale live recording state after process recovery")
            isRecording = false
            persistState(appContext)
        }
    }

    fun stopIfGuidedTripEnded(context: Context) {
        if (!AppModeController.isGuidedTrip && isRecording) {
            stopRecording(context)
        }
    }

    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    private fun persistState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_RECORDING, isRecording)
            .apply()
    }
}
