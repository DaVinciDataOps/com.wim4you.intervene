package com.wim4you.intervene.distress

import com.firebase.geofire.GeoLocation
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.data.AddressData
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.mappings.DataMappings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DistressFirebaseWriter {

    private const val TAG = "DistressFirebaseWriter"

    fun inactiveUpdateMap(): Map<String, Any?> = mapOf(
        "active" to false,
        "g" to null,
        "l" to null,
    )

    suspend fun markDistressInactive(firebaseUid: String) {
        FirebaseDatabaseProvider.reference()
            .child("distress")
            .child(firebaseUid)
            .updateChildren(inactiveUpdateMap())
            .awaitTask()
        SecureLog.i(TAG, "Distress marked inactive for $firebaseUid")
    }

    fun markDistressInactiveAsync(
        firebaseUid: String,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        FirebaseDatabaseProvider.reference()
            .child("distress")
            .child(firebaseUid)
            .updateChildren(inactiveUpdateMap())
            .addOnSuccessListener {
                SecureLog.i(TAG, "Distress marked inactive for $firebaseUid")
            }
            .addOnFailureListener { exception ->
                SecureLog.e(TAG, "Failed to mark distress inactive for $firebaseUid", exception)
                onFailure?.invoke(exception)
            }
    }

    suspend fun pushDistress(
        personData: PersonData,
        latitude: Double,
        longitude: Double,
        address: AddressData = unknownAddress(),
        init: Boolean = false,
    ) {
        val firebaseUid = FirebaseAuthManager.ensureSignedIn()
        val geoLocation = GeoLocation(latitude, longitude)
        val distressDataMap = DataMappings.toDistressDataMap(
            personData = personData,
            firebaseUid = firebaseUid,
            geoLocation = geoLocation,
            address = address,
            init = init,
        )
        if (init) {
            distressDataMap["startTime"] = System.currentTimeMillis()
        }
        FirebaseDatabaseProvider.reference()
            .child("distress")
            .child(firebaseUid)
            .updateChildren(distressDataMap)
            .awaitTask()
        SecureLog.i(TAG, "Distress pushed to Firebase for $firebaseUid")
    }

    private fun unknownAddress() = AddressData(
        street = "Unknown location",
        city = "Unknown location",
        country = "Unknown location",
    )

    private suspend fun com.google.android.gms.tasks.Task<Void>.awaitTask() {
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(Unit) }
            addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
