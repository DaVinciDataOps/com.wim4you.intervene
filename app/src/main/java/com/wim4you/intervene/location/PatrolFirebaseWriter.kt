package com.wim4you.intervene.location

import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import android.util.Log
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.FirebaseUtils
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.profilepicture.ProfilePictureSharingCoordinator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PatrolFirebaseWriter {

    private const val TAG = "PatrolFirebaseWriter"

    fun inactiveUpdateMap(): Map<String, Any?> = mapOf(
        "active" to false,
        "g" to null,
        "l" to null,
    )

    suspend fun markPatrolInactive(firebaseUid: String) {
        val database = FirebaseDatabaseProvider.reference()
        val patrolRef = database.child("patrols").child(firebaseUid)
        patrolRef.updateChildren(inactiveUpdateMap()).awaitTask()
        removeFromGeoIndex(database, firebaseUid)
        SecureLog.i(TAG, "Patrol marked inactive for $firebaseUid")
    }

    fun markPatrolInactiveAsync(
        firebaseUid: String,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        val database = FirebaseDatabaseProvider.reference()
        val patrolRef = database.child("patrols").child(firebaseUid)
        patrolRef.updateChildren(inactiveUpdateMap())
            .addOnSuccessListener {
                removeFromGeoIndex(
                    database = database,
                    firebaseUid = firebaseUid,
                    onFailure = onFailure,
                )
            }
            .addOnFailureListener { exception ->
                SecureLog.e(TAG, "Failed to mark patrol inactive for $firebaseUid", exception)
                onFailure?.invoke(exception)
            }
    }

    private fun removeFromGeoIndex(
        database: com.google.firebase.database.DatabaseReference,
        firebaseUid: String,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        GeoFire(database.child("patrols")).removeLocation(firebaseUid) { _, error ->
            if (error != null) {
                SecureLog.e(TAG, "Failed to remove patrol from geo index for $firebaseUid", error.toException())
                onFailure?.invoke(error.toException())
            }
        }
    }

    private suspend fun removeFromGeoIndex(
        database: com.google.firebase.database.DatabaseReference,
        firebaseUid: String,
    ) {
        suspendCancellableCoroutine { continuation ->
            GeoFire(database.child("patrols")).removeLocation(firebaseUid) { _, error ->
                if (error != null) {
                    continuation.resumeWithException(error.toException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    suspend fun pushPatrol(
        vigilante: VigilanteData,
        latitude: Double,
        longitude: Double,
        context: android.content.Context,
    ) {
        val firebaseUid = FirebaseUtils.ensureReady()
        val geoLocation = GeoLocation(latitude, longitude)
        val profilePictureUrl = ProfilePictureSharingCoordinator.getPublishedUrl(context)
        val patrolDataMap = buildMap<String, Any?> {
            put("l", listOf(geoLocation.latitude, geoLocation.longitude))
            put("g", GeoFireUtils.getGeoHashForLocation(geoLocation))
            put("vigilanteId", vigilante.id)
            put("name", vigilante.name)
            put("time", System.currentTimeMillis())
            put("active", true)
            put("fcmToken", null)
            put("photoUrl", profilePictureUrl)
        }
        FirebaseDatabaseProvider.reference()
            .child("patrols")
            .child(firebaseUid)
            .updateChildren(patrolDataMap)
            .awaitTask()
        Log.i(TAG, "Patrol pushed to Firebase at patrols/$firebaseUid")
        SecureLog.i(TAG, "Patrol pushed to Firebase for $firebaseUid")
    }

    private suspend fun com.google.android.gms.tasks.Task<Void>.awaitTask() {
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(Unit) }
            addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
