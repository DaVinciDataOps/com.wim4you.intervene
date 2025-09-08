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
import com.firebase.geofire.GeoQueryEventListener
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.PermissionsUtils
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
    private val refVigilanteLoc = FirebaseDatabase.getInstance().reference.child("vigilanteLoc")
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

        //fetchInitialData()
        startListeningForPatrols()
        startListeningForDistress()
    }

    private fun fetchInitialData() {
        val thirtyMinutesAgo = System.currentTimeMillis() - EXPIRY_TIME_IN_MS
        coroutineScope.launch {
            fetchInitialPatrols(thirtyMinutesAgo)
            fetchInitialDistress(thirtyMinutesAgo)
        }
    }

    private suspend fun fetchInitialPatrols(thirtyMinutesAgo: Long) {
        try {
            val patrolSnapshot = refVigilanteLoc
                .orderByChild("time").startAt(thirtyMinutesAgo.toDouble())
                .get().await()

            val patrolLocationDataList = mutableListOf<PatrolLocationData>()
            for (child in patrolSnapshot.children) {
                val patrolLocationData = child.getValue(PatrolLocationData::class.java)
                patrolLocationData?.let {
                    if (it.isActive == true) {
                        patrolLocationDataList.add(it)
                    }
                }
            }
            sendLocationUpdate(patrolLocationDataList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun fetchInitialDistress(thirtyMinutesAgo: Long) {
        try {
            val distressSnapshot = refDistress
                .orderByChild("time").startAt(thirtyMinutesAgo.toDouble())
                .get().await()

            val distressDataList = mutableListOf<DistressLocationData>()
            for (child in distressSnapshot.children) {
                val distressData = child.getValue(DistressLocationData::class.java)
                distressData?.let {
                    if(distressData.isActive == true)
                        distressDataList.add(it)
                }
            }
            sendDistressUpdate(distressDataList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startListeningForPatrols() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val geoFire = GeoFire(refVigilanteLoc)
        val vigilanteListeners = mutableMapOf<String, ValueEventListener>()


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
                        geoQuery = geoFire.queryAtLocation(userLocation, 2.0).apply {
                            addGeoQueryEventListener(object : GeoQueryEventListener {
                                override fun onKeyEntered(key: String, geoLocation: GeoLocation) {
                                    // Add a continuous listener for this vigilante
                                    val listener = object : ValueEventListener {
                                        override fun onDataChange(snapshot: DataSnapshot) {
                                            val patrolLocationData =
                                                snapshot.getValue(PatrolLocationData::class.java)
                                            patrolLocationData?.let {
                                                val expiredTime =
                                                    System.currentTimeMillis() - EXPIRY_TIME_IN_MS
                                                if (it.isActive == true && it.time!! >= expiredTime) {
                                                    // Update or add to the list
                                                    val index =
                                                        patrolLocationDataList.indexOfFirst { data -> data.id == it.id }
                                                    if (index >= 0) {
                                                        // Update existing entry
                                                        patrolLocationDataList[index] = it
                                                    } else {
                                                        // Add new entry
                                                        patrolLocationDataList.add(it)
                                                    }
                                                    sendLocationUpdate(patrolLocationDataList)
                                                } else {
                                                    // Remove inactive or expired vigilante
                                                    patrolLocationDataList.removeAll { data -> data.id == it.id }
                                                    sendLocationUpdate(patrolLocationDataList)
                                                }
                                            }
                                        }

                                        override fun onCancelled(error: DatabaseError) {
                                            Log.e(
                                                "FirebaseError",
                                                "Error fetching patrol data: $error"
                                            )
                                        }
                                    }
                                    // Store the listener for this key
                                    vigilanteListeners[key] = listener
                                    refVigilanteLoc.child(key).addValueEventListener(listener)
                                }

                                override fun onKeyExited(key: String) {
                                    // Remove from list and stop listening
                                    patrolLocationDataList.removeAll { it.id == key }
                                    vigilanteListeners[key]?.let {
                                        refVigilanteLoc.child(key).removeEventListener(it)
                                    }
                                    vigilanteListeners.remove(key)
                                    sendLocationUpdate(patrolLocationDataList)
                                }

                                override fun onKeyMoved(key: String, location: GeoLocation) {
                                    // Update location in the list if the vigilante exists
                                    val index = patrolLocationDataList.indexOfFirst { it.id == key }
                                    if (index >= 0) {
                                        patrolLocationDataList[index] =
                                            patrolLocationDataList[index].copy(
                                                location = PatrolLocationData.GeoFireLocation(
                                                    latitude = location.latitude,
                                                    longitude = location.longitude
                                                ),
                                                time = System.currentTimeMillis() // Update time to reflect movement
                                            )
                                        sendLocationUpdate(patrolLocationDataList)
                                    }
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


//        val thirtyMinutesAgo = System.currentTimeMillis() - EXPIRY_TIME_IN_MS
//        patrolListener = object : ValueEventListener {
//            override fun onDataChange(snapshot: DataSnapshot) {
//                val patrolLocationDataList = mutableListOf<PatrolLocationData>()
//                for (child in snapshot.children) {
//                    val patrolLocationData = child.getValue(PatrolLocationData::class.java)
//                    patrolLocationData?.let {
//                        if (it.isActive == true) { // Only include active patrols
//                            patrolLocationDataList.add(it)
//                        }
//                    }
//                }
//                // Broadcast the list of active patrol data
//                sendLocationUpdate(patrolLocationDataList)
//            }
//
//            override fun onCancelled(error: DatabaseError) {
//                // Log error or handle as needed (e.g., notify UI of failure)
//            }
//        }
//        refVigilanteLoc
//            .orderByChild("time")
//            .startAt(thirtyMinutesAgo.toDouble())
//            .addValueEventListener(patrolListener!!)
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