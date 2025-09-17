package com.wim4you.intervene.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQuery
import com.firebase.geofire.GeoQueryDataEventListener
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.getValue
import com.wim4you.intervene.R
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData

class LocationTrackerService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var geoFirePatrols: GeoFire
    private lateinit var geoFireDistress: GeoFire
    private lateinit var geoQueryPatrols: GeoQuery
    private lateinit var geoQueryDistress: GeoQuery

    private val patrolLocationDataList = mutableListOf<PatrolLocationData>()
    private val distressLocationDataList = mutableListOf<DistressLocationData>()

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        patrolLocationDataList.clear()
        distressLocationDataList.clear()
        setupFirebase()
        createNotificationChannel()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun setupFirebase() {
        val database = FirebaseDatabase.getInstance()
        val patrolsRef = database.getReference("patrols")
        val distressRef = database.getReference("distress")
        geoFirePatrols = GeoFire(patrolsRef)
        geoFireDistress = GeoFire(distressRef)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LocationTracker Service Channel",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Tracking")
            .setContentText("Tracking your location for patrols and distress calls")
            .setSmallIcon(R.drawable.ic_location) // Replace with your icon
            .build()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            15000L // Update every 15 seconds
        ).setMinUpdateIntervalMillis(10000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLocation = GeoLocation(location.latitude, location.longitude)
                    queryNearbyPatrols(userLocation)
                    queryNearbyDistress(userLocation)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun validData(patrol:PatrolLocationData?): Boolean{
        val expiredTime = System.currentTimeMillis() - EXPIRY_TIME_IN_MS
        return patrol != null && patrol.isActive == true && patrol.time!! > expiredTime
    }

    private fun validData(distress:DistressLocationData?): Boolean{
        val expiredTime = System.currentTimeMillis() - EXPIRY_TIME_IN_MS
        return distress != null && distress.isActive == true && distress.time!! > expiredTime
    }

    private fun queryNearbyPatrols(location: GeoLocation) {
        // Query patrols within 2km
        geoQueryPatrols = geoFirePatrols.queryAtLocation(location, 2.0)
        geoQueryPatrols.addGeoQueryDataEventListener(object : GeoQueryDataEventListener {
            override fun onDataEntered(dataSnapshot: DataSnapshot, location: GeoLocation) {
                // Active patrol found within 2km
                val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>()
                if (validData(patrolLocationData)) {
                    patrolLocationData?.let { patrolLocationData ->
                        patrolLocationData.id = patrolLocationData.vigilanteId
                        patrolLocationData.locationArray =
                            listOf(location.latitude, location.longitude)
                        val index =
                            patrolLocationDataList.indexOfFirst { it.id == dataSnapshot.key }

                        if (index == -1)
                            patrolLocationDataList.add(patrolLocationData)
                        else
                            patrolLocationDataList[index] = patrolLocationData

                        broadcastPatrolUpdate(patrolLocationDataList)
                    }
                }
            }

            override fun onDataExited(dataSnapshot: DataSnapshot) {
                // Patrol moved out of range
                patrolLocationDataList.removeAll { it.id == dataSnapshot.key }
                broadcastPatrolUpdate(patrolLocationDataList)
            }

            override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
                // Patrol moved within range
                val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>()
                if (validData(patrolLocationData)) {
                    patrolLocationData?.let { patrolLocationData ->
                        patrolLocationData.locationArray =
                            listOf(location.latitude, location.longitude)
                        val index =
                            patrolLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
                        val moved = listOf(location.latitude, location.longitude)
                        patrolLocationData.id = patrolLocationData.vigilanteId
                        patrolLocationData.locationArray = moved
                        if (index == -1)
                            patrolLocationDataList.add(patrolLocationData)
                        else
                            patrolLocationDataList[index] = patrolLocationData

                        broadcastPatrolUpdate(patrolLocationDataList)
                    }
                }
            }

            override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation){
                val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>()
                if (validData(patrolLocationData)) {
                    patrolLocationData?.let { patrolLocationData ->
                        val index =
                            patrolLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
                        val moved = listOf(location.latitude, location.longitude)
                        patrolLocationData.id = patrolLocationData.vigilanteId
                        patrolLocationData.locationArray = moved
                        if (index == -1)
                            patrolLocationDataList.add(patrolLocationData)
                        else
                            patrolLocationDataList[index] = patrolLocationData

                        broadcastPatrolUpdate(patrolLocationDataList)
                    }
                }
            }

            override fun onGeoQueryReady() {
                // Initial query complete
            }

            override fun onGeoQueryError(error: DatabaseError) {
                // Handle error
            }
        })
    }


    private fun queryNearbyDistress(location: GeoLocation) {
        // Query distress calls within 2km
        geoQueryDistress = geoFireDistress.queryAtLocation(location, 2.0)
        geoQueryDistress.addGeoQueryDataEventListener(object : GeoQueryDataEventListener {
            override fun onDataEntered(dataSnapshot: DataSnapshot, location: GeoLocation) {
                // Distress found within 2km
                val distressLocationData = dataSnapshot.getValue<DistressLocationData>()
                if (validData(distressLocationData)) {
                    distressLocationData?.let { distressLocationData ->
                        distressLocationData.id = distressLocationData.personId
                        distressLocationData.locationArray =
                            listOf(location.latitude, location.longitude)
                        val index =
                            distressLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
                        if (index == -1)
                            distressLocationDataList.add(distressLocationData)
                        else
                            distressLocationDataList[index] = distressLocationData
                    }
                } else {
                    distressLocationDataList.removeAll { it.id == dataSnapshot.key }
                }
                broadcastDistressUpdate(distressLocationDataList)
            }

            override fun onDataExited(dataSnapshot: DataSnapshot) {
                // Distress moved out of range
                distressLocationDataList.removeAll { it.id == dataSnapshot.key }
                broadcastDistressUpdate(distressLocationDataList)
            }

            override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
                // Distress moved within range
                val distressLocationData = dataSnapshot.getValue<DistressLocationData>()
                if (validData(distressLocationData)) {
                    distressLocationData?.let {
                        distressLocationData.id = distressLocationData.personId
                        distressLocationData.locationArray =
                            listOf(location.latitude, location.longitude)
                        val index =
                            distressLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
                        if (index == -1)
                            distressLocationDataList.add(distressLocationData)
                        else
                            distressLocationDataList[index] = distressLocationData

                        broadcastDistressUpdate(distressLocationDataList)
                    }
                }
            }

            override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation) {
                // Distress data changed
                val distressLocationData = dataSnapshot.getValue<DistressLocationData>()
                if (validData(distressLocationData)) {
                    distressLocationData?.let {
                        distressLocationData.id = distressLocationData.personId
                        distressLocationData.locationArray =
                            listOf(location.latitude, location.longitude)
                        val index =
                            distressLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
                        if (index != -1) {
                            if (distressLocationData.isActive == false)
                                distressLocationDataList.removeAll { it.id == dataSnapshot.key }
                            else
                                distressLocationDataList[index] = distressLocationData

                            broadcastDistressUpdate(distressLocationDataList)
                        }
                    }
                }
            }

            override fun onGeoQueryReady() {
                // Initial query complete
            }

            override fun onGeoQueryError(error: DatabaseError) {
                // Handle error
            }
        })
    }

    private fun broadcastPatrolUpdate(patrolLocationDataList: List<PatrolLocationData>) {
        val intent = Intent(ACTION_PATROL_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_PATROL_DATA, ArrayList(patrolLocationDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDistressUpdate(distressLocationDataList: List<DistressLocationData>) {
        val intent = Intent(ACTION_DISTRESS_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_DISTRESS_DATA, ArrayList(distressLocationDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}