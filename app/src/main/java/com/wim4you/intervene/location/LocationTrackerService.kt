package com.wim4you.intervene.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQuery
import com.firebase.geofire.GeoQueryDataEventListener
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.getValue
import com.wim4you.intervene.AppState
import com.wim4you.intervene.R
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LocationTrackerService : Service() {
    private var geoQuery: GeoQuery? = null
    private lateinit var locationCallback: LocationCallback
    private val channelId = "LocationTrackerServiceChannel"
    private val refPatrolLoc = FirebaseDatabase.getInstance().reference.child("patrols")
    private val refDistress = FirebaseDatabase.getInstance().reference.child("distress")
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var patrolListener: ValueEventListener? = null
    private var distressListener: ValueEventListener? = null
    private val notificationId = 1002

    private val patrolLocationDataList = mutableListOf<PatrolLocationData>()
    val vigilanteListeners = mutableMapOf<String, ValueEventListener>()

    companion object {
        const val ACTION_PATROL_UPDATE = "com.wim4you.intervene.LOCATION_UPDATE"
        const val ACTION_DISTRESS_UPDATE = "com.wim4you.intervene.DISTRESS_UPDATE"
        const val EXTRA_PATROL_DATA = "extra_patrol_data"
        const val EXTRA_DISTRESS_DATA = "extra_distress_data"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "LocationServiceChannel"
        private const val EXPIRY_TIME_IN_MS = 30 * 60 * 1000
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("InterVene")
            .setContentText("Running in the background")
            .setSmallIcon(R.drawable.ic_startstop_patrolling) // Replace with your icon
            .build()

        startForeground(notificationId, notification)

        startListeningForPatrols()
        startListeningForDistress()
    }

    private fun startListeningForPatrols() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val geoFire = GeoFire(refPatrolLoc)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            15000L // Update every 15 seconds
        ).setMinUpdateIntervalMillis(10000L).build()

        // Check permissions
        val permissionsToCheck = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToCheck.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (permissionsToCheck.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            Log.d("LocationTracker", "All required permissions granted")
        } else {
            Log.e("LocationError", "Missing permissions: $permissionsToCheck")
            //broadcastPermissionDenied()
            stopSelf()
            return
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLocation = GeoLocation(location.latitude, location.longitude)
                    if (geoQuery == null) {
                        geoQuery = geoFire.queryAtLocation(userLocation, AppState.DistressRadius).apply {
                            addGeoQueryDataEventListener(object : GeoQueryDataEventListener {

                                override fun onDataEntered(dataSnapshot: DataSnapshot, location: GeoLocation) {
                                    val data = dataSnapshot.value as? Map<*, *>?: run{ return }
                                    Log.d("GeoQuery", "Data entered: ${dataSnapshot.key} at (${location.latitude}, ${location.longitude})")
                                    val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>() ?:
                                        throw IllegalArgumentException("Automatic deserialization failed for ${dataSnapshot.key}")

                                    patrolLocationData.let {
                                        val updatedData = PatrolLocationData(
                                            id = dataSnapshot.key,
                                            geohash = data["g"] as? String ?: "",
                                            locationArray = listOf(location.latitude, location.longitude),
                                            vigilanteId = patrolLocationData.vigilanteId,
                                            name = patrolLocationData.name,
                                            isActive = patrolLocationData.isActive,
                                            time = patrolLocationData.time,
                                            fcmToken = patrolLocationData.fcmToken
                                        )
                                        val expiredTime = System.currentTimeMillis() - EXPIRY_TIME_IN_MS
                                        if (it.isActive == true && it.time!! >= expiredTime) {
                                            val index = patrolLocationDataList.indexOfFirst { data -> data.id == updatedData.id }
                                            if (index >= 0) {
                                                patrolLocationDataList[index] = updatedData
                                            } else {
                                                patrolLocationDataList.add(updatedData)
                                            }
                                            sendLocationUpdate(patrolLocationDataList)
                                        } else {
                                            patrolLocationDataList.removeAll { data -> data.id == updatedData.id }
                                            sendLocationUpdate(patrolLocationDataList)
                                        }
                                    }
                                }

                                override fun onDataExited(dataSnapshot: DataSnapshot) {
                                    Log.d("GeoQuery", "Data exited: ${dataSnapshot.key}")
                                    patrolLocationDataList.removeAll { it.id == dataSnapshot.key }
                                    sendLocationUpdate(patrolLocationDataList)
                                }

                                override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
                                    Log.d("GeoQuery", "Data moved: ${dataSnapshot.key} to (${location.latitude}, ${location.longitude})")
                                    val index = patrolLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
                                    if (index >= 0) {
                                        val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>()
                                            ?: run {
                                                Log.w("FirebaseData", "Automatic deserialization failed for ${dataSnapshot.key}, trying manual")
                                                val data = dataSnapshot.value as? Map<String, Any> ?: return
                                                PatrolLocationData(
                                                    id = (data["vigilanteId"] as? String) ?: dataSnapshot.key,
                                                    geohash = data["g"] as? String,
                                                    locationArray = (data["l"] as? List<*>)?.mapNotNull { it as? Double },
                                                    vigilanteId = data["vigilanteId"] as? String,
                                                    name = data["name"] as? String,
                                                    time = data["time"] as? Long,
                                                    isActive = data["active"] as? Boolean,
                                                    fcmToken = data["fcmToken"] as? String
                                                )
                                            }
                                        patrolLocationData?.let {
                                            val updatedData = it.copy(
                                                locationArray = listOf(location.latitude, location.longitude),
                                                time = System.currentTimeMillis()
                                            )
                                            patrolLocationDataList[index] = updatedData
                                            sendLocationUpdate(patrolLocationDataList)
                                        }
                                    }
                                }

                                override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation) {
                                    // Handle changes to existing data
                                    onDataEntered(dataSnapshot, location)
                                }

                                override fun onGeoQueryReady() {
                                    Log.i("GeoQuery", "Initial data loaded")
                                }

                                override fun onGeoQueryError(error: DatabaseError) {
                                    Log.e("GeoQuery", "Error: $error")
                                }
                            })
                        }
                    } else {
                        geoQuery?.center = userLocation
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            .addOnSuccessListener {
                Log.d("LocationTracker", "Successfully requested location updates")
            }
            .addOnFailureListener { e ->
                Log.e("LocationError", "Failed to request location updates: ${e.message}")
            }
    }

    private fun startListeningForDistress() {
        val thirtyMinutesAgo = System.currentTimeMillis() - EXPIRY_TIME_IN_MS
        distressListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val distressDataList = mutableListOf<DistressLocationData>()
                for (child in snapshot.children) {
                    val distressData = child
                        .getValue(DistressLocationData::class.java)
                    distressData?.let {
                        if(distressData.isActive == true)
                            distressDataList.add(it)
                    }
                }
                sendDistressUpdate(distressDataList)
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }
        refDistress
            .orderByChild("time")
            .startAt(thirtyMinutesAgo.toDouble())
            .addValueEventListener(distressListener!!)
    }

    private fun sendLocationUpdate(patrolLocationDataList: List<PatrolLocationData>) {
        val intent = Intent(ACTION_PATROL_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_PATROL_DATA, ArrayList(patrolLocationDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendDistressUpdate(distressDataList: List<DistressLocationData>) {
        val intent = Intent(ACTION_DISTRESS_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_DISTRESS_DATA, ArrayList(distressDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "LocationTracker Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}