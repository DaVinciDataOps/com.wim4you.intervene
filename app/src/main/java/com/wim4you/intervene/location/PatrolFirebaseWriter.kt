package com.wim4you.intervene.location

import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import android.util.Log
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.FirebaseUtils
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.data.VigilanteData
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PatrolFirebaseWriter {

    private const val TAG = "PatrolFirebaseWriter"

    suspend fun pushPatrol(
        vigilante: VigilanteData,
        latitude: Double,
        longitude: Double,
    ) {
        val firebaseUid = FirebaseUtils.ensureReady()
        val geoLocation = GeoLocation(latitude, longitude)
        val patrolDataMap = mapOf(
            "l" to listOf(geoLocation.latitude, geoLocation.longitude),
            "g" to GeoFireUtils.getGeoHashForLocation(geoLocation),
            "vigilanteId" to vigilante.id,
            "name" to vigilante.name,
            "time" to System.currentTimeMillis(),
            "active" to true,
            "fcmToken" to null,
        )
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
