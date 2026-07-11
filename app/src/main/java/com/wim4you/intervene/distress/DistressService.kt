package com.wim4you.intervene.distress

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.firebase.geofire.GeoLocation
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.Constants
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.R
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.AddressData
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.helpers.LocationProvider
import com.wim4you.intervene.mappings.DataMappings
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.Locale

class DistressService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = Constants.DISTRESS_SERVICE_CHANNEL_ID
    private val notificationId = Constants.DISTRESS_SERVICE_NOTIFICATION_ID
    private val database = FirebaseDatabaseProvider.reference()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var distressJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var personStore: PersonDataRepository
    private lateinit var attributedContext: Context


    companion object   {
        fun stop(context: Context) {
            val intent = Intent(context, DistressService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        attributedContext = createAttributionContext(Constants.DISTRESS_SERVICE_CONTEXT_TAG)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(attributedContext)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(attributedContext).personDataDao())
        LocationProvider.initialize(attributedContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(attributedContext, channelId)
            .setContentTitle("InterVene")
            .setContentText("Running in the background")
            .setSmallIcon(R.drawable.ic_startstop_patrolling) // Replace with your icon
            .build()

        startForeground(notificationId, notification)

        if (AppModeController.isDistressActive) {
            coroutineScope.launch {
                val personData = personStore.fetch()
                if (personData == null) {
                    stopSelf()
                } else {
                    val firebaseUid = try {
                        FirebaseAuthManager.ensureSignedIn()
                    } catch (exception: Exception) {
                        Log.e("DistressService", "Failed to authenticate before distress updates", exception)
                        val message = when (FirebaseAuthManager.authFailureKey(exception)) {
                            "auth_not_configured" -> getString(R.string.chat_error_auth_not_configured)
                            "auth_anonymous_disabled" -> getString(R.string.chat_error_auth_anonymous_disabled)
                            "auth_network" -> getString(R.string.chat_error_auth_network)
                            else -> "Distress could not start: authentication failed."
                        }
                        AppModeController.reportBackgroundFailure(message)
                        stopSelf()
                        return@launch
                    }
                    startDistressUpdates(personData, firebaseUid)
                }
            }
        }
        return START_STICKY
    }

    private fun startDistressUpdates(
        personData: PersonData,
        firebaseUid: String,
    ) {
        if (ContextCompat.checkSelfPermission(
                attributedContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Handle missing permissions (e.g., stop service or notify user)
            stopSelf()
            return
        }

        distressJob?.cancel()
        distressJob = coroutineScope.launch {
            var initialDistressSent = false
            while (isActive && AppModeController.isDistressActive) {
                try {
                    val location = LocationProvider.getLastLocation()
                        ?: getLastKnownLocationFallback()
                    if (location != null) {
                        val geoLocation = GeoLocation(location.latitude, location.longitude)
                        if (!initialDistressSent) {
                            sendDistressToHistory(personData, firebaseUid, geoLocation)
                            sendStartDistressToFirebase(personData, firebaseUid, true, geoLocation)
                            initialDistressSent = true
                        } else {
                            sendStartDistressToFirebase(personData, firebaseUid, false, geoLocation)
                        }
                        delay(15_000)
                    } else {
                        delay(if (initialDistressSent) 15_000 else 2_000)
                    }
                }
                catch (e: Exception) {
                    Log.e("DistressService", "Error getting location: ${e.message}")
                    AppModeController.reportBackgroundFailure("Distress update failed; retrying automatically.")
                    delay(if (initialDistressSent) 5_000 else 2_000)
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

    private suspend fun sendStartDistressToFirebase(
        personData: PersonData,
        firebaseUid: String,
        init: Boolean,
        geoLocation: GeoLocation,
    ) {
        if (!AppModeController.isDistressActive) return

        val address = getAddress(geoLocation)
        val distressDataMap = DataMappings.toDistressDataMap(
            personData,
            firebaseUid,
            geoLocation,
            address,
            init,
        )

        if (init) {
            distressDataMap["startTime"] = System.currentTimeMillis()
        }

        database.child("distress").child(firebaseUid)
            .updateChildren(distressDataMap)
            .addOnSuccessListener {
                if (!AppModeController.isDistressActive) {
                    sendStopDistressToFirebase(firebaseUid)
                    return@addOnSuccessListener
                }
                SecureLog.i("DistressService", "Distress location updated")
            }
            .addOnFailureListener { exception ->
                SecureLog.e("DistressService", "Failed to update distress location", exception)
                AppModeController.reportBackgroundFailure("Distress location sync failed; retrying.")
            }
    }

    private suspend fun sendDistressToHistory(
        personData: PersonData,
        firebaseUid: String,
        geoLocation: GeoLocation,
    ) {
        val address = getAddress(geoLocation)
        val distressDataMap = DataMappings.toDistressDataMap(personData, firebaseUid, geoLocation, address)
        val personDataMap = mapOf(
            "id" to firebaseUid,
            "alias" to personData.alias,
            "gender" to personData.gender,
            "age" to personData.age
        )

        val historyMap = mutableMapOf(
            "personData" to personDataMap,
            "distress" to distressDataMap,
            "time" to System.currentTimeMillis()
        )

        val id = "${firebaseUid}_${System.currentTimeMillis()}"

        database.child("distress_history").child(id).
        setValue(historyMap)
            .addOnSuccessListener {
                SecureLog.i("DistressService", "Distress history saved")
            }
            .addOnFailureListener { exception ->
                SecureLog.e("DistressService", "Failed to save distress history", exception)
                AppModeController.reportBackgroundFailure("Distress history sync failed.")
            }
    }

    private fun sendStopDistressToFirebase(firebaseUid: String) {
        DistressFirebaseWriter.markDistressInactiveAsync(firebaseUid) {
            AppModeController.reportBackgroundFailure("Could not mark distress as stopped in cloud.")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Distress Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private suspend fun getAddress(geoLocation: GeoLocation): AddressData =
        withContext(Dispatchers.IO) {
            val unknown = "Unknown location"
            val apiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = apiAvailability.isGooglePlayServicesAvailable(attributedContext)
            if (resultCode != ConnectionResult.SUCCESS) {
                return@withContext AddressData(street = unknown, city = unknown, country = unknown)
            }

            val geocoder = Geocoder(attributedContext, Locale.getDefault())
            val addresses = geocoder.getFromLocation(geoLocation.latitude, geoLocation.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses.first()
                return@withContext AddressData(
                    street = "${address.thoroughfare} ${address.subThoroughfare}",
                    city = address.locality,
                    country = address.countryName,
                )
            }

            AddressData(street = unknown, city = unknown, country = unknown)
        }

    override fun onDestroy() {
        distressJob?.cancel()
        distressJob = null

        if (!AppModeController.isDistressActive) {
            serviceScope.launch {
                val firebaseUid = try {
                    FirebaseAuthManager.ensureSignedIn()
                } catch (exception: Exception) {
                    Log.e("DistressService", "Failed to authenticate before stopping distress")
                    AppModeController.reportBackgroundFailure("Could not authenticate to stop distress cleanly.")
                    return@launch
                }
                sendStopDistressToFirebase(firebaseUid)
            }
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}