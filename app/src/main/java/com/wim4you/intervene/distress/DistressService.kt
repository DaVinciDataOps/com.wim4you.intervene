package com.wim4you.intervene.distress

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import com.wim4you.intervene.AppState
import com.wim4you.intervene.Constants
import com.wim4you.intervene.R
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.AddressData
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.helpers.LocationProvider
import com.wim4you.intervene.mappings.DataMappings
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class DistressService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = Constants.DISTRESS_SERVICE_CHANNEL_ID
    private val notificationId = Constants.DISTRESS_SERVICE_NOTIFICATION_ID
    private val database = FirebaseDatabase.getInstance().reference
    private val geoFire = GeoFire(database.child("distress"))
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var distressJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var personStore: PersonDataRepository
    private lateinit var attributedContext: Context


    companion object   {
        fun stop(context: Context) {
            val intent = Intent(context, DistressService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        attributedContext = createAttributionContext(Constants.DISTRESS_SERVICE_CONTEXT_TAG)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(attributedContext)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(attributedContext).personDataDao())
        LocationProvider.initialize(attributedContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(attributedContext, channelId)
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
                  startDistressUpdates(personData, true,AppState.isDistressState)
                }
            }
        }
        return START_STICKY
    }

    private fun startDistressUpdates(personData: PersonData, init:Boolean, active: Boolean) {
        if (ContextCompat.checkSelfPermission(
                attributedContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Handle missing permissions (e.g., stop service or notify user)
            stopSelf()
            return

        }

        distressJob?.cancel()
        distressJob = coroutineScope.launch {
            var location = LocationProvider.getLastLocation()
            location?.let{
                val geoLocation = GeoLocation(location.latitude, location.longitude)
                sendDistressToHistory(personData, geoLocation)
            }

            while (isActive && AppState.isDistressState) {
                try {
                    location = LocationProvider.getLastLocation()
                    location?.let {
                        val geoLocation = GeoLocation(it.latitude, it.longitude)
                        sendStartDistressToFirebase(personData, false, geoLocation)
                    }
                    delay(15_000)
                }
                catch (e: Exception) {
                    Log.e("DistressService", "Error getting location: ${e.message}")
                }
            }
        }
    }

    private fun sendStartDistressToFirebase(personData: PersonData, init:Boolean, geoLocation: GeoLocation) {
        val address = getAddress(geoLocation)
        val distressDataMap = DataMappings.toDistressDataMap(personData, geoLocation, address, init)

        if (init) {
            distressDataMap["startTime"] = System.currentTimeMillis()
        }

        database.child("distress").child(personData.id.toString()).
        updateChildren(distressDataMap)
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving patrol:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving patrol:")
            }
    }

    private fun sendDistressToHistory(personData: PersonData, geoLocation: GeoLocation ){
        val address = getAddress(geoLocation)
        val distressDataMap = DataMappings.toDistressDataMap(personData, geoLocation, address)
        val personDataMap = mapOf(
            "id" to personData.id,
            "alias" to personData.alias,
            "gender" to personData.gender,
            "age" to personData.age
        )

        val historyMap = mutableMapOf(
            "personData" to personDataMap,
            "distress" to distressDataMap,
            "time" to System.currentTimeMillis()
        )

        val id = "${personData.id}_${System.currentTimeMillis()}"

        database.child("distress_history").child(id).
        setValue(historyMap)
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving distress history:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving distress history:")
            }
    }

    private fun sendStopDistressToFirebase(id:String) {
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

    private fun getAddress(geoLocation: GeoLocation): AddressData {
        var unknown = "Unknown location"
        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(attributedContext)
        if (resultCode != ConnectionResult.SUCCESS) {
            return AddressData(street = unknown, city = unknown, country = unknown)
        }

        val geocoder = Geocoder(attributedContext, Locale.getDefault())
        val addresses = geocoder.getFromLocation(geoLocation.latitude, geoLocation.longitude, 1)
        if (!addresses.isNullOrEmpty()){
            var address = addresses.first()
            return AddressData(street = "${address.thoroughfare} ${address.subThoroughfare}", city = address.locality, country = address.countryName)
        }

        return AddressData(street = unknown, city = unknown, country = unknown)
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
                    sendStopDistressToFirebase(personData.id )
                }
            }
        }

        distressJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}