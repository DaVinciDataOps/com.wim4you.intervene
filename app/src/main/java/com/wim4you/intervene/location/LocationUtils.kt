package com.wim4you.intervene.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.LocationUpdateWorker
import com.wim4you.intervene.R
import java.util.concurrent.TimeUnit

object LocationUtils {
    private const val LOCATION_PERMISSION_REQUEST_CODE = 100

    fun scheduleLocationUpdates(context: Context) {
        val locationWorkRequest = PeriodicWorkRequestBuilder<LocationUpdateWorker>(
            repeatInterval = 15, // Minimum interval is 15 minutes for PeriodicWorkRequest
            repeatIntervalTimeUnit = TimeUnit.SECONDS
        ).build()

        WorkManager.Companion.getInstance(context)
            .enqueueUniquePeriodicWork(
                "location_work",
                ExistingPeriodicWorkPolicy.KEEP, // Use REPLACE to update if needed
                locationWorkRequest
            )
    }
    fun getLocation(context: Context, callback: (LatLng?) -> Unit) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    location?.let {
                        val currentLatLng = LatLng(it.latitude, it.longitude)
                        callback(currentLatLng)
                    } ?: callback(null)
                }.addOnFailureListener { e ->
                    Log.e("MapError", "Failed to get location: ${e.message}")
                    Toast.makeText(context, R.string.error_location_failed, Toast.LENGTH_SHORT).show()
                    callback(null)
                }
        } else {
            if (context is AppCompatActivity) {
                ActivityCompat.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
            callback(null)
        }
    }

    fun setLocation(context: Context, callback: (LatLng?) -> Unit){
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val locationRequest =
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                        .setMinUpdateIntervalMillis(5000)
                        .setMaxUpdates(1) // Get only one update
                        .build()

                val locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val newLocation = locationResult.lastLocation
                        newLocation?.let {
                            val currentLatLng = LatLng(it.latitude, it.longitude)
                            callback(currentLatLng)
                        } ?: callback(null)
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                }

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
            catch(e: Exception){
                Log.e("MapError", "Failed to get location: ${e.message}")
                callback(null)
            }
        } else {
            callback(null)
        }
    }
}