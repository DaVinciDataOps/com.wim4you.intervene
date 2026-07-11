package com.wim4you.intervene.location
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.Constants
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.FirebaseUtils
import com.wim4you.intervene.R
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.helpers.LocationProvider
import com.wim4you.intervene.profilepicture.ProfilePictureSharingCoordinator
import com.wim4you.intervene.repository.VigilanteDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PatrolService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = Constants.PATROL_SERVICE_CHANNEL_ID
    private val notificationId = Constants.PATROL_SERVICE_NOTIFICATION_ID
    private val database = FirebaseDatabaseProvider.reference()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var patrolJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var vigilanteStore: VigilanteDataRepository
    private val geoFire = GeoFire(database.child("patrols"))
    private lateinit var attributedContext: Context


    override fun onCreate() {
        super.onCreate()
        attributedContext = createAttributionContext(Constants.PATROL_SERVICE_CONTEXT_TAG)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(attributedContext)
        vigilanteStore = VigilanteDataRepository(DatabaseProvider.getDatabase(attributedContext).vigilanteDataDao())
        LocationProvider.initialize(attributedContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(attributedContext, channelId)
            .setContentTitle("InterVene")
            .setContentText("Running in the background")
            .setSmallIcon(R.drawable.ic_startstop_patrolling)
            .build()

        startForeground(notificationId, notification)

        if (AppModeController.isPatrolling) {
            coroutineScope.launch {
                AppModeController.vigilante = vigilanteStore.fetch()
                val vigilanteData = AppModeController.vigilante
                if (vigilanteData == null) {
                    stopSelf()
                } else {
                    val firebaseUid = try {
                        FirebaseUtils.ensureReady()
                    } catch (exception: Exception) {
                        Log.e("PatrolService", "Failed to authenticate before patrol updates")
                        AppModeController.reportBackgroundFailure("Patrol could not start: authentication failed.")
                        stopSelf()
                        return@launch
                    }
                    startLocationUpdates(vigilanteData, firebaseUid)
                }
            }
        }
        return START_STICKY
    }

    private fun startLocationUpdates(vigilanteData: VigilanteData, firebaseUid: String) {
        if (ContextCompat.checkSelfPermission(
                attributedContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Handle missing permissions (e.g., stop service or notify user)
            stopSelf()
            return
        }

        patrolJob?.cancel()
        patrolJob = coroutineScope.launch {
            var initialPatrolSent = false
            while (isActive && AppModeController.isPatrolling) {
                try {
                    val location = LocationProvider.getLastLocation()
                        ?: getLastKnownLocationFallback()
                    if (location != null) {
                        val geoLocation = GeoLocation(location.latitude, location.longitude)

                        val patrolLocationData = PatrolLocationData(
                            id = vigilanteData.id,
                            vigilanteId = vigilanteData.id,
                            name = vigilanteData.name,
                            time = System.currentTimeMillis(),
                            isActive = AppModeController.isPatrolling,
                            fcmToken = null // Replace with actual FCM token if needed
                        )
                        sendToFirebase(patrolLocationData, firebaseUid, geoLocation)
                        initialPatrolSent = true
                        delay(15_000)
                    } else {
                        delay(if (initialPatrolSent) 15_000 else 2_000)
                    }
                }
                catch (e: Exception) {
                    Log.e("PatrolService", "Error getting patrol location: ${e.message}", e)
                    AppModeController.reportBackgroundFailure("Patrol update failed; retrying automatically.")
                    delay(if (initialPatrolSent) 5_000 else 2_000)
                }
            }
        }
    }

    private suspend fun getLastKnownLocationFallback(): android.location.Location? =
        suspendCancellableCoroutine { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { continuation.resume(null) }
        }

    private fun sendToFirebase(
        patrolLocationData: PatrolLocationData,
        firebaseUid: String,
        geoLocation: GeoLocation,
    ) {
        if (!AppModeController.isPatrolling) return

        patrolLocationData.id = patrolLocationData.vigilanteId
        patrolLocationData.l = listOf(geoLocation.latitude, geoLocation.longitude)
        patrolLocationData.g = GeoFireUtils.getGeoHashForLocation(geoLocation)

        val profilePictureUrl = ProfilePictureSharingCoordinator.getPublishedUrl(attributedContext)
        val patrolDataMap = buildMap<String, Any?> {
            put("l", listOf(geoLocation.latitude, geoLocation.longitude))
            put("g", GeoFireUtils.getGeoHashForLocation(geoLocation))
            put("vigilanteId", patrolLocationData.vigilanteId)
            put("name", patrolLocationData.name)
            put("time", patrolLocationData.time)
            put("active", patrolLocationData.isActive)
            put("fcmToken", patrolLocationData.fcmToken)
            put("photoUrl", profilePictureUrl)
        }

        database.child("patrols").child(firebaseUid)
            .updateChildren(patrolDataMap)
            .addOnSuccessListener {
                if (!AppModeController.isPatrolling) {
                    sendStopPatrolToFirebase(firebaseUid)
                    return@addOnSuccessListener
                }
                SecureLog.i("PatrolService", "Patrol location updated")
            }
            .addOnFailureListener { exception ->
                SecureLog.e("PatrolService", "Failed to update patrol location", exception)
                AppModeController.reportBackgroundFailure("Patrol location sync failed; retrying.")
            }
    }

    private fun sendStopPatrolToFirebase(firebaseUid: String) {
        PatrolFirebaseWriter.markPatrolInactiveAsync(firebaseUid) {
            AppModeController.reportBackgroundFailure("Could not mark patrol as stopped in cloud.")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Patrol Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!AppModeController.isPatrolling) {
            serviceScope.launch {
                val firebaseUid = try {
                    FirebaseAuthManager.ensureSignedIn()
                } catch (exception: Exception) {
                    Log.e("PatrolService", "Failed to authenticate before stopping patrol")
                    AppModeController.reportBackgroundFailure("Could not authenticate to stop patrol cleanly.")
                    return@launch
                }
                sendStopPatrolToFirebase(firebaseUid)
            }
        }
        patrolJob?.cancel()
        coroutineScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}