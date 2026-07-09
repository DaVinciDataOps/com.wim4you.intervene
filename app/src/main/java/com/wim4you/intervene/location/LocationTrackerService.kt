package com.wim4you.intervene.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoFireUtils
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
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.Constants
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.R
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.repository.MapLocationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackerService : Service() {
    @Inject lateinit var mapLocationRepository: MapLocationRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var geoFirePatrols: GeoFire
    private lateinit var geoFireDistress: GeoFire
    private lateinit var geoQueryPatrols: GeoQuery
    private lateinit var geoQueryDistress: GeoQuery

    private val patrolLocationDataList = mutableListOf<PatrolLocationData>()
    private val distressLocationDataList = mutableListOf<DistressLocationData>()
    private lateinit var attributedContext: Context

    private var lastPatrolQueryCenter: GeoLocation? = null
    private var lastDistressQueryCenter: GeoLocation? = null
    private var patrolListenersAttached = false
    private var distressListenersAttached = false
    private var isFirebaseReady = false

    companion object {
        const val ACTION_PATROL_UPDATE = "com.wim4you.intervene.LOCATION_UPDATE"
        const val ACTION_DISTRESS_UPDATE = "com.wim4you.intervene.DISTRESS_UPDATE"
        const val EXTRA_PATROL_DATA = "extra_patrol_data"
        const val EXTRA_DISTRESS_DATA = "extra_distress_data"
        private const val NOTIFICATION_ID = Constants.LOCATION_TRACKER_SERVICE_NOTIFICATION_ID
        private const val CHANNEL_ID = Constants.LOCATION_TRACKER_SERVICE_CHANNEL_ID
        private const val QUERY_RECENTER_THRESHOLD_KM = 0.5
    }

    private val patrolQueryListener = object : GeoQueryDataEventListener {
        override fun onDataEntered(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handlePatrolSnapshot(dataSnapshot, location)
        }

        override fun onDataExited(dataSnapshot: DataSnapshot) {
            patrolLocationDataList.removeAll { it.id == dataSnapshot.key }
            broadcastPatrolUpdate(patrolLocationDataList)
        }

        override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handlePatrolSnapshot(dataSnapshot, location)
        }

        override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handlePatrolSnapshot(dataSnapshot, location)
        }

        override fun onGeoQueryReady() = Unit

        override fun onGeoQueryError(error: DatabaseError) {
            Log.e("LocationTrackerService", "Patrol geo query error: ${error.message}")
        }
    }

    private val distressQueryListener = object : GeoQueryDataEventListener {
        override fun onDataEntered(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handleDistressSnapshot(dataSnapshot, location)
            broadcastDistressUpdate(distressLocationDataList)
        }

        override fun onDataExited(dataSnapshot: DataSnapshot) {
            distressLocationDataList.removeAll { it.id == dataSnapshot.key }
            broadcastDistressUpdate(distressLocationDataList)
        }

        override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handleDistressSnapshot(dataSnapshot, location)
            broadcastDistressUpdate(distressLocationDataList)
        }

        override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation) {
            val distressLocationData = dataSnapshot.getValue<DistressLocationData>()
            if (validData(distressLocationData)) {
                handleDistressSnapshot(dataSnapshot, location)
            } else {
                distressLocationDataList.removeAll { it.id == dataSnapshot.key }
            }
            broadcastDistressUpdate(distressLocationDataList)
        }

        override fun onGeoQueryReady() = Unit

        override fun onGeoQueryError(error: DatabaseError) {
            Log.e("LocationTrackerService", "Distress geo query error: ${error.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        attributedContext = createAttributionContext(Constants.TRACKER_SERVICE_CONTEXT_TAG)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(attributedContext)
        patrolLocationDataList.clear()
        distressLocationDataList.clear()
        serviceScope.launch {
            try {
                FirebaseAuthManager.ensureSignedIn()
                setupFirebase()
                isFirebaseReady = true
            } catch (exception: Exception) {
                Log.e("LocationTrackerService", "Failed to authenticate before geo queries", exception)
            }
        }
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
        geoFirePatrols = GeoFire(database.getReference("patrols"))
        geoFireDistress = GeoFire(database.getReference("distress"))
        SecureLog.d("LocationTrackerService", "Firebase geo queries initialized")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LocationTracker Service Channel",
            NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(attributedContext, CHANNEL_ID)
            .setContentTitle("Location Tracking")
            .setContentText("Tracking your location for patrols and distress calls")
            .setSmallIcon(R.drawable.ic_location)
            .build()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            AppModeController.LOCATION_UPDATE_INTERVAL_MS
        ).setMinUpdateIntervalMillis(10_000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLocation = GeoLocation(location.latitude, location.longitude)
                    ensurePatrolQuery(userLocation)
                    ensureDistressQuery(userLocation)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(attributedContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun validData(patrol: PatrolLocationData?): Boolean {
        val expiredTime = System.currentTimeMillis() - AppModeController.LOCATION_DATA_EXPIRY_MS
        return patrol != null && patrol.isActive == true && patrol.time!! > expiredTime
    }

    private fun validData(distress: DistressLocationData?): Boolean {
        val expiredTime = System.currentTimeMillis() - AppModeController.LOCATION_DATA_EXPIRY_MS
        return distress != null && distress.isActive == true && distress.time!! > expiredTime
    }

    private fun shouldRecenterQuery(lastCenter: GeoLocation?, newCenter: GeoLocation): Boolean {
        if (lastCenter == null) return true
        val distanceKm = GeoFireUtils.getDistanceBetween(lastCenter, newCenter)
        return distanceKm > QUERY_RECENTER_THRESHOLD_KM
    }

    private fun ensurePatrolQuery(location: GeoLocation) {
        if (!isFirebaseReady) return
        if (patrolListenersAttached && !shouldRecenterQuery(lastPatrolQueryCenter, location)) {
            return
        }
        if (patrolListenersAttached && ::geoQueryPatrols.isInitialized) {
            geoQueryPatrols.removeAllListeners()
        }
        patrolLocationDataList.clear()
        broadcastPatrolUpdate(patrolLocationDataList)
        lastPatrolQueryCenter = location
        geoQueryPatrols = geoFirePatrols.queryAtLocation(location, AppModeController.GEO_QUERY_RADIUS_KM)
        geoQueryPatrols.addGeoQueryDataEventListener(patrolQueryListener)
        patrolListenersAttached = true
    }

    private fun ensureDistressQuery(location: GeoLocation) {
        if (!isFirebaseReady) return
        if (distressListenersAttached && !shouldRecenterQuery(lastDistressQueryCenter, location)) {
            return
        }
        if (distressListenersAttached && ::geoQueryDistress.isInitialized) {
            geoQueryDistress.removeAllListeners()
        }
        distressLocationDataList.clear()
        broadcastDistressUpdate(distressLocationDataList)
        lastDistressQueryCenter = location
        geoQueryDistress = geoFireDistress.queryAtLocation(location, AppModeController.GEO_QUERY_RADIUS_KM)
        geoQueryDistress.addGeoQueryDataEventListener(distressQueryListener)
        distressListenersAttached = true
    }

    private fun handlePatrolSnapshot(dataSnapshot: DataSnapshot, location: GeoLocation) {
        val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>()
        if (!validData(patrolLocationData)) return
        patrolLocationData?.let { patrol ->
            patrol.id = patrol.vigilanteId
            patrol.l = listOf(location.latitude, location.longitude)
            val index = patrolLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
            if (index == -1) {
                patrolLocationDataList.add(patrol)
            } else {
                patrolLocationDataList[index] = patrol
            }
            broadcastPatrolUpdate(patrolLocationDataList)
        }
    }

    private fun handleDistressSnapshot(dataSnapshot: DataSnapshot, location: GeoLocation) {
        val distressLocationData = dataSnapshot.getValue<DistressLocationData>()
        if (!validData(distressLocationData)) {
            distressLocationDataList.removeAll { it.id == dataSnapshot.key }
            return
        }
        distressLocationData?.let { distress ->
            distress.id = distress.personId
            distress.l = listOf(location.latitude, location.longitude)
            val index = distressLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
            if (index == -1) {
                distressLocationDataList.add(distress)
            } else {
                distressLocationDataList[index] = distress
            }
        }
    }

    private fun broadcastPatrolUpdate(patrolLocationDataList: List<PatrolLocationData>) {
        mapLocationRepository.updatePatrolLocations(patrolLocationDataList)
    }

    private fun broadcastDistressUpdate(distressLocationDataList: List<DistressLocationData>) {
        mapLocationRepository.updateDistressLocations(distressLocationDataList)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::geoQueryPatrols.isInitialized) {
            geoQueryPatrols.removeAllListeners()
        }
        if (::geoQueryDistress.isInitialized) {
            geoQueryDistress.removeAllListeners()
        }
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } else {
            Log.w("LocationTrackerService", "locationCallback was not initialized")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
