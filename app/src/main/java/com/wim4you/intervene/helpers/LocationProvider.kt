package com.wim4you.intervene.helpers

import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

object LocationProvider {
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Initialize the FusedLocationProviderClient (call this from your DistressService or Application class)
    fun initialize(context: Context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    }

    suspend fun getLastLocation(maxAgeMillis: Long = 60_000): Location? =
        suspendCancellableCoroutine { continuation ->
            try {
                val cancellationTokenSource = CancellationTokenSource()
                val request = CurrentLocationRequest.Builder()
                    .setMaxUpdateAgeMillis(maxAgeMillis)
                    .build()

                fusedLocationClient.getCurrentLocation(request, cancellationTokenSource.token)
                    .addOnSuccessListener { newLocation ->
                        continuation.resume(newLocation) { cause, _, _ -> cancellationTokenSource }
                    }
                    .addOnFailureListener { exception ->
                        continuation.resumeWithException(exception)
                    }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }
}