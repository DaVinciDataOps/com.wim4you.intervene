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
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.getValue
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.Constants
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
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
    private lateinit var geoQueryPatrols: GeoQuery
    private lateinit var geoFireDistress: GeoFire
    private lateinit var geoQueryDistress: GeoQuery

    private val patrolLocationDataList = mutableListOf<PatrolLocationData>()
    private val distressLocationDataList = mutableListOf<DistressLocationData>()
    private val distressDetailListeners = mutableMapOf<String, ValueEventListener>()
    private val patrolDetailListeners = mutableMapOf<String, ValueEventListener>()
    private lateinit var attributedContext: Context

    private var lastPatrolQueryCenter: GeoLocation? = null
    private var lastDistressQueryCenter: GeoLocation? = null
    private var patrolListenersAttached = false
    private var distressListenersAttached = false
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
            detachPatrolDetailListener(dataSnapshot.key)
            removePatrolByKey(dataSnapshot.key)
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
        }

        override fun onDataExited(dataSnapshot: DataSnapshot) {
            detachDistressDetailListener(dataSnapshot.key)
            removeDistressByKey(dataSnapshot.key)
        }

        override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handleDistressSnapshot(dataSnapshot, location)
        }

        override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation) {
            handleDistressSnapshot(dataSnapshot, location)
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
        val database = FirebaseDatabaseProvider.reference()
        geoFirePatrols = GeoFire(database.child("patrols"))
        geoFireDistress = GeoFire(database.child("distress"))
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
        } else {
            mapLocationRepository.clearOwnPatrol()
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
        ensureDistressQuery(userLocation)
    }

    private fun bootstrapGeoQueries() {
        pendingQueryLocation?.let { location ->
            ensurePatrolQuery(location)
            ensureDistressQuery(location)
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
        detachAllPatrolDetailListeners()
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
        detachAllDistressDetailListeners()
        distressLocationDataList.clear()
        broadcastDistressUpdate(distressLocationDataList)
        lastDistressQueryCenter = location
        geoQueryDistress = geoFireDistress.queryAtLocation(location, AppModeController.GEO_QUERY_RADIUS_KM)
        geoQueryDistress.addGeoQueryDataEventListener(distressQueryListener)
        distressListenersAttached = true
    }

    private fun handleDistressSnapshot(dataSnapshot: DataSnapshot, location: GeoLocation) {
        val distress = parseDistressSnapshot(dataSnapshot)
        if (distress == null || !validData(distress)) {
            removeDistressByKey(dataSnapshot.key)
            return
        }
        distress.id = dataSnapshot.key
        if (distress.personId.isNullOrBlank()) {
            distress.personId = dataSnapshot.key
        }
        distress.l = listOf(location.latitude, location.longitude)
        val index = distressLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
        if (index == -1) {
            distressLocationDataList.add(distress)
        } else {
            distressLocationDataList[index] = distress
        }
        attachDistressDetailListener(dataSnapshot.key)
        broadcastDistressUpdate(distressLocationDataList)
    }

    private fun attachDistressDetailListener(key: String?) {
        if (key.isNullOrBlank() || key in distressDetailListeners) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                val hasGeoHash = !snapshot.child("g").getValue(String::class.java).isNullOrBlank()
                if (!active || !hasGeoHash) {
                    removeDistressByKey(snapshot.key)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LocationTrackerService", "Distress detail listener cancelled: ${error.message}")
            }
        }
        distressDetailListeners[key] = listener
        FirebaseDatabaseProvider.reference().child("distress").child(key)
            .addValueEventListener(listener)
    }

    private fun detachDistressDetailListener(key: String?) {
        if (key.isNullOrBlank()) return
        val listener = distressDetailListeners.remove(key) ?: return
        FirebaseDatabaseProvider.reference().child("distress").child(key)
            .removeEventListener(listener)
    }

    private fun detachAllDistressDetailListeners() {
        distressDetailListeners.keys.toList().forEach(::detachDistressDetailListener)
    }

    private fun parseDistressSnapshot(snapshot: DataSnapshot): DistressLocationData? {
        snapshot.getValue(DistressLocationData::class.java)?.let { parsed ->
            return if (parsed.isActive == true) parsed else null
        }
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
        if (!validData(patrolLocationData)) {
            removePatrolByKey(dataSnapshot.key)
            return
        }
        patrolLocationData?.let { patrol ->
            patrol.id = dataSnapshot.key
            patrol.l = listOf(location.latitude, location.longitude)
            val index = patrolLocationDataList.indexOfFirst { it.id == dataSnapshot.key }
            if (index == -1) {
                patrolLocationDataList.add(patrol)
            } else {
                patrolLocationDataList[index] = patrol
            }
            attachPatrolDetailListener(dataSnapshot.key)
            broadcastPatrolUpdate(patrolLocationDataList)
        }
    }

    private fun attachPatrolDetailListener(key: String?) {
        if (key.isNullOrBlank() || key in patrolDetailListeners) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                val hasGeoHash = !snapshot.child("g").getValue(String::class.java).isNullOrBlank()
                if (!active || !hasGeoHash) {
                    removePatrolByKey(snapshot.key)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LocationTrackerService", "Patrol detail listener cancelled: ${error.message}")
            }
        }
        patrolDetailListeners[key] = listener
        FirebaseDatabaseProvider.reference().child("patrols").child(key)
            .addValueEventListener(listener)
    }

    private fun detachPatrolDetailListener(key: String?) {
        if (key.isNullOrBlank()) return
        val listener = patrolDetailListeners.remove(key) ?: return
        FirebaseDatabaseProvider.reference().child("patrols").child(key)
            .removeEventListener(listener)
    }

    private fun detachAllPatrolDetailListeners() {
        patrolDetailListeners.keys.toList().forEach(::detachPatrolDetailListener)
    }

    private fun broadcastPatrolUpdate(patrolLocationDataList: List<PatrolLocationData>) {
        mapLocationRepository.updatePatrolLocations(patrolLocationDataList)
    }

    private fun removePatrolByKey(key: String?) {
        if (key == null) return
        detachPatrolDetailListener(key)
        if (patrolLocationDataList.removeAll { it.id == key }) {
            broadcastPatrolUpdate(patrolLocationDataList)
        }
    }

    private fun removeDistressByKey(key: String?) {
        if (key == null) return
        detachDistressDetailListener(key)
        if (distressLocationDataList.removeAll { it.id == key }) {
            broadcastDistressUpdate(distressLocationDataList)
        }
    }

    private fun broadcastDistressUpdate(distressLocationDataList: List<DistressLocationData>) {
        mapLocationRepository.updateDistressLocations(distressLocationDataList.toList())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::geoQueryPatrols.isInitialized) {
            geoQueryPatrols.removeAllListeners()
        }
        if (::geoQueryDistress.isInitialized) {
            geoQueryDistress.removeAllListeners()
        }
        detachAllPatrolDetailListeners()
        detachAllDistressDetailListeners()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } else {
            Log.w("LocationTrackerService", "locationCallback was not initialized")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
