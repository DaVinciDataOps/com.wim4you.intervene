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
import com.google.firebase.database.FirebaseDatabase
import com.wim4you.intervene.AppState
import com.wim4you.intervene.Constants
import com.wim4you.intervene.R
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.helpers.LocationProvider
import com.wim4you.intervene.repository.VigilanteDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PatrolService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = Constants.PATROL_SERVICE_CHANNEL_ID
    private val notificationId = Constants.PATROL_SERVICE_NOTIFICATION_ID
    private val database = FirebaseDatabase.getInstance().reference
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
            while (isActive && AppState.isPatrolling) {
                try {
                    val location = LocationProvider.getLastLocation()
                    location?.let {
                        val geoLocation = GeoLocation(it.latitude, it.longitude)

                        val patrolLocationData = PatrolLocationData(
                            id = vigilanteData.id,
                            vigilanteId = vigilanteData.id,
                            name = vigilanteData.name,
                            time = System.currentTimeMillis(),
                            isActive = AppState.isPatrolling,
                            fcmToken = null // Replace with actual FCM token if needed
                        )
                        sendToFirebase(patrolLocationData,geoLocation)
                    }
                    delay(15_000)
                }
                catch (e: Exception) {

                }
            }
        }
    }

    private fun sendToFirebase(patrolLocationData: PatrolLocationData, geoLocation: GeoLocation) {
        patrolLocationData.id = patrolLocationData.vigilanteId
        patrolLocationData.l = listOf(geoLocation.latitude, geoLocation.longitude)
        patrolLocationData.g = GeoFireUtils.getGeoHashForLocation(geoLocation)

        val patrolDataMap = mapOf(
            "l" to listOf(geoLocation.latitude, geoLocation.longitude),
            "g" to GeoFireUtils.getGeoHashForLocation(geoLocation),
            "vigilanteId" to patrolLocationData.vigilanteId,
            "name" to patrolLocationData.name,
            "time" to patrolLocationData.time,
            "active" to patrolLocationData.isActive,
            "fcmToken" to patrolLocationData.fcmToken
        )

        database.child("patrols").child(patrolLocationData.id.toString()).
        updateChildren(patrolDataMap)
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving patrol:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving patrol:")
            }
    }

    private fun sendToFirebase(id:String, active: Boolean) {
        database.child("patrols").child(id)
            .updateChildren(mapOf("active" to active))
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving patrol:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving patrol:")
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
        serviceScope.launch {
            val vigilanteData = vigilanteStore.fetch();
            if(vigilanteData == null) {
                stopSelf()
            }
            else {
                sendToFirebase(vigilanteData.id, false)
            }
        }
        patrolJob?.cancel()
        coroutineScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}