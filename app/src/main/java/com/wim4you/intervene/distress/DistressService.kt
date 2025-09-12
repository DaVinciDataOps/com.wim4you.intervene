package com.wim4you.intervene.distress

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.database.FirebaseDatabase
import com.wim4you.intervene.AppState
import com.wim4you.intervene.R
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

class DistressService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = "TripServiceChannel"
    private val notificationId = 1004
    private val database = FirebaseDatabase.getInstance().reference
    private val geoFire = GeoFire(database.child("distress"))
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var distressJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var personStore: PersonDataRepository

    companion object   {
        fun stop(context: Context) {
            val intent = Intent(context, DistressService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(this).personDataDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("InterVene")
            .setContentText("Running in the background")
            .setSmallIcon(R.drawable.ic_startstop_patrolling) // Replace with your icon
            .build()

        startForeground(notificationId, notification)

        if (AppState.isDistressState) {
            coroutineScope.launch {
                var personData = personStore.fetch();
                if(personData == null) {
                  stopSelf()
                }
                else {
                  startDistressUpdates(personData,AppState.isDistressState)
                }
            }
        }
        return START_STICKY
    }

    private fun startDistressUpdates(personData: PersonData, active: Boolean) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Handle missing permissions (e.g., stop service or notify user)
            stopSelf()
            return
        }

        distressJob?.cancel()
        distressJob = coroutineScope.launch {
            while (isActive && AppState.isDistressState) {
                try {
                    val location = getLastLocation()
                    location?.let {
                        val geoLocation = GeoLocation(it.latitude, it.longitude)
                        val distressData = data(personData.id,it, isActive = active)
                        sendStartStopDistressToFirebase(distressData, geoLocation)
                    }
                    delay(15_000)
                }
                catch (e: Exception) {
                    Log.e("DistressService", "Error getting location: ${e.message}")
                }
            }
        }
    }

    private fun data(id:String, geoLocation: Location, isActive: Boolean): DistressLocationData {
        val distressLocationData = DistressLocationData(
            id = id,
            personId = id,
            locationArray =  listOf(geoLocation.latitude, geoLocation.longitude),
            time = System.currentTimeMillis(),
            fcmToken = null,
            isActive = isActive
        )
        return distressLocationData
    }

    private suspend fun getLastLocation(maxAgeMillis: Long = 60_000): Location? =
        suspendCancellableCoroutine { continuation ->
            try {

                val cancellationTokenSource = CancellationTokenSource()
                val request = CurrentLocationRequest.Builder()
                    .setMaxUpdateAgeMillis(maxAgeMillis)
                    .build()

                fusedLocationClient.getCurrentLocation(request, cancellationTokenSource.token)
                    .addOnSuccessListener { newLocation ->
                        continuation.resume(newLocation) { cause, _, _ -> cancellationTokenSource }
                    }
                    .addOnFailureListener { exception ->
                        continuation.resumeWithException(exception)
                    }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }

    private fun sendStartStopDistressToFirebase(distressLocationData: DistressLocationData, geoLocation: GeoLocation) {
        geoFire.setLocation(distressLocationData.id, geoLocation) { key, error ->
            if (error != null) {
                Log.e("Firebase", "Error saving GeoFire location: ${error.message}")
            } else {
                Log.i("Firebase", "Success saving GeoFire location for key: $key")
            }
        }

        val distressDataMap = mapOf(
            "personId" to distressLocationData.personId,
            "time" to distressLocationData.time,
            "active" to distressLocationData.isActive,
            "fcmToken" to distressLocationData.fcmToken
        )

        database.child("distress").child(distressLocationData.id.toString()).
        updateChildren(distressDataMap)
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving patrol:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving patrol:")
            }
    }

    private fun sendStartStopDistressToFirebase(id:String) {
        database.child("distress").child(id)
            .updateChildren(mapOf("active" to false))
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving distress:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving distress:")
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

    override fun onDestroy() {
        super.onDestroy()

        if (!AppState.isDistressState) {
            serviceScope.launch {
                val personData = personStore.fetch();
                if(personData == null) {
                    stopSelf()
                }
                else {
                    sendStartStopDistressToFirebase(personData.id )
                }
            }
        }

        distressJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}