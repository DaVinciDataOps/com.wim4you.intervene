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
import com.google.firebase.database.ChildEventListener
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
import com.wim4you.intervene.helpers.DistanceUtils
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
    private lateinit var geoQueryPatrols: GeoQuery

    private val patrolLocationDataList = mutableListOf<PatrolLocationData>()
    private val distressLocationDataList = mutableListOf<DistressLocationData>()
    private val distressCache = mutableMapOf<String, DistressLocationData>()
    private lateinit var attributedContext: Context

    private var lastPatrolQueryCenter: GeoLocation? = null
    private var distressQueryCenter: GeoLocation? = null
    private var patrolListenersAttached = false
    private var distressChildListenerAttached = false
    private var distressChildListener: ChildEventListener? = null
    private var isFirebaseReady = false
    private var pendingQueryLocation: GeoLocation? = null

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

    private val distressChildListenerImpl = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            handleDistressChildSnapshot(snapshot)
        }

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
            handleDistressChildSnapshot(snapshot)
        }

        override fun onChildRemoved(snapshot: DataSnapshot) {
            val key = snapshot.key ?: return
            distressCache.remove(key)
            publishDistressNearby()
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            handleDistressChildSnapshot(snapshot)
        }

        override fun onCancelled(error: DatabaseError) {
            Log.e("LocationTrackerService", "Distress child listener error: ${error.message}")
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
                bootstrapGeoQueries()
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
                    onTrackerLocation(GeoLocation(location.latitude, location.longitude))
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(attributedContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    onTrackerLocation(GeoLocation(it.latitude, it.longitude))
                }
            }
        }
    }

    private fun onTrackerLocation(userLocation: GeoLocation) {
        pendingQueryLocation = userLocation
        if (AppModeController.isPatrolling) {
            val vigilante = AppModeController.vigilante
            if (vigilante != null) {
                mapLocationRepository.ensureOwnPatrol(
                    vigilante,
                    userLocation.latitude,
                    userLocation.longitude,
                )
            }
        }
        if (AppModeController.isDistressActive) {
            val person = AppModeController.person
            if (person != null) {
                mapLocationRepository.ensureOwnDistress(
                    person,
                    userLocation.latitude,
                    userLocation.longitude,
                    FirebaseAuthManager.currentUid(),
                )
            }
        }
        ensurePatrolQuery(userLocation)
        ensureDistressObservation(userLocation)
    }

    private fun bootstrapGeoQueries() {
        pendingQueryLocation?.let { location ->
            ensurePatrolQuery(location)
            ensureDistressObservation(location)
            return
        }
        if (ActivityCompat.checkSelfPermission(attributedContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                onTrackerLocation(GeoLocation(it.latitude, it.longitude))
            }
        }
    }

    private fun validData(patrol: PatrolLocationData?): Boolean {
        val expiredTime = System.currentTimeMillis() - AppModeController.LOCATION_DATA_EXPIRY_MS
        return patrol != null && patrol.isActive == true && patrol.time!! > expiredTime
    }

    private fun validData(distress: DistressLocationData?): Boolean {
        val expiredTime = System.currentTimeMillis() - AppModeController.LOCATION_DATA_EXPIRY_MS
        val timestamp = distress?.time ?: distress?.startTime ?: 0L
        return distress != null && distress.isActive == true && timestamp > expiredTime
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

    private fun ensureDistressObservation(center: GeoLocation) {
        if (!isFirebaseReady) return
        distressQueryCenter = center
        if (!distressChildListenerAttached) {
            val distressRef = FirebaseDatabase.getInstance().getReference("distress")
            distressRef.addChildEventListener(distressChildListenerImpl)
            distressChildListener = distressChildListenerImpl
            distressChildListenerAttached = true
            SecureLog.d("LocationTrackerService", "Distress child listener attached")
        }
        publishDistressNearby()
    }

    private fun handleDistressChildSnapshot(snapshot: DataSnapshot) {
        val key = snapshot.key ?: return
        val distress = parseDistressSnapshot(snapshot)
        if (distress == null || !validData(distress)) {
            distressCache.remove(key)
        } else {
            distress.id = key
            if (distress.personId.isNullOrBlank()) {
                distress.personId = key
            }
            distressCache[key] = distress
        }
        publishDistressNearby()
    }

    private fun publishDistressNearby() {
        val center = distressQueryCenter
        if (center == null) {
            distressLocationDataList.clear()
            broadcastDistressUpdate(distressLocationDataList)
            return
        }
        val radiusMeters = AppModeController.GEO_QUERY_RADIUS_KM * 1000.0
        val nearby = distressCache.values.filter { distress ->
            val lat = distress.latitude ?: return@filter false
            val lng = distress.longitude ?: return@filter false
            validData(distress) &&
                DistanceUtils.metersBetween(center.latitude, center.longitude, lat, lng) <= radiusMeters
        }
        distressLocationDataList.clear()
        distressLocationDataList.addAll(nearby)
        broadcastDistressUpdate(nearby)
    }

    private fun parseDistressSnapshot(snapshot: DataSnapshot): DistressLocationData? {
        snapshot.getValue(DistressLocationData::class.java)?.let { return it }
        val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
        if (!active) return null
        val coordinates = snapshot.child("l").children.mapNotNull { it.getValue(Double::class.java) }
        if (coordinates.size < 2) return null
        return DistressLocationData(
            id = snapshot.key,
            personId = snapshot.child("personId").getValue(String::class.java) ?: snapshot.key,
            alias = snapshot.child("alias").getValue(String::class.java),
            address = snapshot.child("address").getValue(String::class.java),
            g = snapshot.child("g").getValue(String::class.java),
            l = coordinates,
            startTime = snapshot.child("startTime").getValue(Long::class.java),
            time = snapshot.child("time").getValue(Long::class.java),
            fcmToken = snapshot.child("fcmToken").getValue(String::class.java),
            isActive = active,
            safeWordHash = snapshot.child("safeWordHash").getValue(String::class.java),
            city = snapshot.child("city").getValue(String::class.java),
            country = snapshot.child("country").getValue(String::class.java),
        )
    }

    private fun handlePatrolSnapshot(dataSnapshot: DataSnapshot, location: GeoLocation) {
        val patrolLocationData = dataSnapshot.getValue<PatrolLocationData>()
        if (!validData(patrolLocationData)) return
        patrolLocationData?.let { patrol ->
            patrol.id = dataSnapshot.key
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
        distressChildListener?.let { listener ->
            FirebaseDatabase.getInstance().getReference("distress").removeEventListener(listener)
        }
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } else {
            Log.w("LocationTrackerService", "locationCallback was not initialized")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
