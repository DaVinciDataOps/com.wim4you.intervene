package com.wim4you.intervene.location
import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import com.wim4you.intervene.AppState
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.fbdata.DistressData
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resumeWithException

class TripService : Service() {
    private val database = FirebaseDatabase.getInstance().reference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var personStore: PersonDataRepository

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(this).personDataDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppState.isGuidedTrip) {

            coroutineScope.launch {
                var personData = personStore.fetch();
                if(personData == null) {
                  stopSelf()
                }
                else {
                  startLocationUpdates(personData)
                }
            }
        }
        return START_STICKY
    }

    private fun startLocationUpdates(personData: PersonData) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Handle missing permissions (e.g., stop service or notify user)
            stopSelf()
            return
        }

        locationJob?.cancel()
        locationJob = coroutineScope.launch {
            while (isActive && AppState.IsDistressState) {
                try {
                    val location = getLastLocation()
                    location?.let {
                        val distressData = data(personData.id,it)
                        sendToFirebase(distressData)
                    }
                    delay(15_000)
                }
                catch (e: Exception) {

                }
            }
        }
    }

    private fun data(id:String, geoLocation: Location): DistressData {
        val distressData = DistressData(
            id = id,
            personId = id,
            location = mapOf(
                "latitude" to geoLocation.latitude,
                "longitude" to geoLocation.longitude
            ),
            time = System.currentTimeMillis(),
            fcmToken = null // Replace with actual FCM token if needed
        )
        return distressData
    }

    private suspend fun getLastLocation(): Location? = suspendCancellableCoroutine { continuation ->
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location, null)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        } catch (e: SecurityException) {
            continuation.resumeWithException(e)
        }
    }

    private fun sendToFirebase(distressData: DistressData) {
        database.child("distress").child(distressData.id).setValue(distressData)
            .addOnSuccessListener {
                Log.e("Firebase", "Success saving distress:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving distress:")
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationJob?.cancel()
        coroutineScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}