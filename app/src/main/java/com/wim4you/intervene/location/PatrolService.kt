package com.wim4you.intervene.location
import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.database.FirebaseDatabase
import com.wim4you.intervene.AppState
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.fbdata.PatrolData
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
import kotlin.coroutines.resumeWithException

class PatrolService : Service() {
    private val database = FirebaseDatabase.getInstance().reference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var vigilanteStore: VigilanteDataRepository

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        vigilanteStore = VigilanteDataRepository(DatabaseProvider.getDatabase(this).vigilanteDataDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppState.isPatrolling) {
            coroutineScope.launch {
                AppState.vigilante = vigilanteStore.fetch();
                var vigilanteData = AppState.vigilante
                if(vigilanteData  == null) {
                  stopSelf()
                }
                else {
                  startLocationUpdates(vigilanteData)
                }
            }
        }
        return START_STICKY
    }

    private fun startLocationUpdates(vigilanteData: VigilanteData) {
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
            while (isActive && AppState.isPatrolling) {
                try {
                    val location = getLastLocation()
                    location?.let {
                        val patrolData = PatrolData(
                            id = vigilanteData.id,
                            vigilanteId = vigilanteData.id,
                            name = vigilanteData.name,
                            location = mapOf(
                                "latitude" to it.latitude,
                                "longitude" to it.longitude
                            ),
                            Time = System.currentTimeMillis(),
                            isActive = true,
                            fcmToken = null // Replace with actual FCM token if needed
                        )
                        sendToFirebase(patrolData)
                    }
                    delay(15_000)
                }
                catch (e: Exception) {

                }
            }
        }
    }

    private suspend fun getLastLocation(maxAgeMillis: Long = 60_000): Location? = suspendCancellableCoroutine { continuation ->
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

    private fun sendToFirebase(patrolData: PatrolData) {
        database.child("vigilanteLoc").child(patrolData.id).setValue(patrolData)
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving patrol:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving patrol:")
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