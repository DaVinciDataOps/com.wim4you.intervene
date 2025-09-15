package com.wim4you.intervene.distress

import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import android.util.Log
import java.util.Locale

object DistressNotificationManager {
    fun sendDistressNotification(context: Context) {
        // Initialize FusedLocationProviderClient
        val fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

        // Check for location permissions
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Get last known location
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        // Get latitude and longitude
                        val latitude = location.latitude
                        val longitude = location.longitude

                        // Get human-readable address using Geocoder
                        val geocoder = Geocoder(context, Locale.getDefault())
                        var addressText = "Unknown location"
                        try {
                            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                            if (!addresses.isNullOrEmpty()) {
                                val address = addresses[0]
                                addressText = buildString {
                                    append(address.thoroughfare ?: "") // Street name
                                    append(" ")
                                    append(address.subThoroughfare ?: "") // Street number
                                    append(", ")
                                    append(address.locality ?: "") // City
                                    append(", ")
                                    append(address.countryName ?: "") // Country
                                }.trim().removeSuffix(",")
                            }
                        } catch (e: Exception) {
                            Log.e("Geocoder", "Failed to get address: ${e.message}")
                        }

                        // Reference to Firebase Realtime Database
                        val database = FirebaseDatabase.getInstance().reference
                        val notificationRef = database.child("notifications").push()

                        // Create notification data payload with geolocation
                        val notificationData = mapOf(
                            "title" to "Distress Call",
                            "message" to "A distress signal has been activated!",
                            "latitude" to latitude,
                            "longitude" to longitude,
                            "address" to addressText,
                            "timestamp" to ServerValue.TIMESTAMP,
                            "status" to "pending"
                        )

                        // Write to Realtime Database
                        notificationRef.setValue(notificationData)
                            .addOnSuccessListener {
                                Log.d("Notification", "Notification with location sent to Firebase")
                            }
                            .addOnFailureListener { e ->
                                Log.e("Notification", "Failed to send notification: ${e.message}")
                            }
                    } else {
                        Log.e("Location", "Location is null")
                        // Fallback: Send notification without location
                        sendFallbackNotification(context)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Location", "Failed to get location: ${e.message}")
                    // Fallback: Send notification without location
                    sendFallbackNotification(context)
                }
        } else {
            Log.e("Location", "Location permission not granted")
            // Fallback: Send notification without location
            sendFallbackNotification(context)
        }
    }

    private fun sendFallbackNotification(context: Context) {
        val database = FirebaseDatabase.getInstance().reference
        val notificationRef = database.child("notifications").push()

        val notificationData = mapOf(
            "title" to "Distress Call",
            "message" to "A distress signal has been activated! Location unavailable.",
            "address" to "Unknown",
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        )

        notificationRef.setValue(notificationData)
            .addOnSuccessListener {
                Log.d("Notification", "Fallback notification sent to Firebase")
            }
            .addOnFailureListener { e ->
                Log.e("Notification", "Failed to send fallback notification: ${e.message}")
            }
    }
}