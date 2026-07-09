package com.wim4you.intervene

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.database.database
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.distress.DistressService
import com.wim4you.intervene.distress.DistressSoundService
import com.wim4you.intervene.location.PatrolService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single owner for app mode transitions (guided trip, patrol, distress).
 * All start/stop paths should go through this object.
 */
object AppModeController {

    const val GEO_QUERY_RADIUS_KM = 2.0
    const val LOCATION_DATA_EXPIRY_MS = 30 * 60 * 1000L
    const val LOCATION_UPDATE_INTERVAL_MS = 15_000L
    private const val TAG = "AppModeController"
    private const val PREFS_NAME = "app_mode_state"
    private const val KEY_IS_PATROLLING = "is_patrolling"
    private const val KEY_IS_GUIDED_TRIP = "is_guided_trip"
    private const val KEY_IS_DISTRESS_ACTIVE = "is_distress_active"

    var isPatrolling: Boolean = false
        private set

    var isGuidedTrip: Boolean = false
        private set

    var isDistressActive: Boolean = false
        private set

    var selectedDistressCall: Int = -1
    var vigilante: VigilanteData? = null
    var snackBarMessage: String = ""
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isPatrolling = prefs.getBoolean(KEY_IS_PATROLLING, false)
        isGuidedTrip = prefs.getBoolean(KEY_IS_GUIDED_TRIP, false)
        isDistressActive = prefs.getBoolean(KEY_IS_DISTRESS_ACTIVE, false)
    }

    fun reportBackgroundFailure(message: String) {
        snackBarMessage = message
    }

    private fun persistState() {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_PATROLLING, isPatrolling)
            .putBoolean(KEY_IS_GUIDED_TRIP, isGuidedTrip)
            .putBoolean(KEY_IS_DISTRESS_ACTIVE, isDistressActive)
            .apply()
    }

    fun startGuidedTrip(): Boolean {
        if (isPatrolling) return false
        isGuidedTrip = true
        persistState()
        return true
    }

    suspend fun stopGuidedTrip(context: Context) {
        isGuidedTrip = false
        persistState()
        deactivateDistress(context)
    }

    fun startPatrol(context: Context): Boolean {
        if (isGuidedTrip) return false
        isPatrolling = true
        persistState()
        startPatrolService(context)
        return true
    }

    fun stopPatrol(context: Context) {
        isPatrolling = false
        persistState()
        stopPatrolService(context)
    }

    fun activateDistress(context: Context) {
        isDistressActive = true
        persistState()
        startDistressService(context)
        DistressSoundService.start(context)
    }

    suspend fun deactivateDistress(context: Context) {
        isDistressActive = false
        persistState()
        DistressSoundService.stop(context)
        stopDistressService(context)
        clearDistressInFirebase(context)
    }

    private suspend fun clearDistressInFirebase(context: Context) {
        withContext(Dispatchers.IO) {
            val firebaseUid = try {
                FirebaseAuthManager.ensureSignedIn()
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to authenticate before clearing distress", exception)
                return@withContext
            }
            Firebase.database.getReference("distress").child(firebaseUid)
                .updateChildren(mapOf("active" to false))
                .addOnSuccessListener {
                    Log.i(TAG, "Distress marked inactive in Firebase")
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to mark distress inactive", exception)
                }
        }
    }

    private fun startPatrolService(context: Context) {
        val attributedContext = context.createAttributionContext(Constants.PATROL_SERVICE_CONTEXT_TAG)
        val intent = Intent(attributedContext, PatrolService::class.java)
        startForegroundServiceCompat(attributedContext, intent)
    }

    private fun stopPatrolService(context: Context) {
        val attributedContext = context.createAttributionContext(Constants.PATROL_SERVICE_CONTEXT_TAG)
        attributedContext.stopService(Intent(attributedContext, PatrolService::class.java))
    }

    private fun startDistressService(context: Context) {
        val intent = Intent(context, DistressService::class.java)
        startForegroundServiceCompat(context, intent)
    }

    private fun stopDistressService(context: Context) {
        context.stopService(Intent(context, DistressService::class.java))
    }

    private fun startForegroundServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
