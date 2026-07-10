package com.wim4you.intervene.repository

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.helpers.SafeWordHasher
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class InterveningRepository @Inject constructor() {

    private val database = FirebaseDatabaseProvider.reference()

    suspend fun verifySafeWord(distressFirebaseUid: String, safeWord: String): Boolean {
        return try {
            val snapshot = database.child("distress").child(distressFirebaseUid).getOnce()
            val storedHash = snapshot.child("safeWordHash").getValue(String::class.java)
            if (storedHash.isNullOrBlank()) {
                SecureLog.w(TAG, "No safeWordHash on distress node $distressFirebaseUid")
                false
            } else {
                SafeWordHasher.matches(safeWord, storedHash)
            }
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to verify safe word", exception)
            false
        }
    }

    suspend fun registerIntervention(
        distressFirebaseUid: String,
        vigilante: VigilanteData,
    ): Result<Unit> {
        return try {
            val vigilanteFirebaseUid = FirebaseAuthManager.ensureSignedIn()
            val interventionMap = mapOf(
                "id" to "${distressFirebaseUid}_$vigilanteFirebaseUid",
                "timestamp" to System.currentTimeMillis(),
                "personId" to distressFirebaseUid,
                "vigilanteId" to vigilante.id,
                "vigilanteName" to vigilante.name,
            )
            database.child("intervening")
                .child(distressFirebaseUid)
                .child(vigilanteFirebaseUid)
                .setValueOnce(interventionMap)
            Result.success(Unit)
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to register intervention", exception)
            Result.failure(exception)
        }
    }

    private suspend fun com.google.firebase.database.DatabaseReference.getOnce(): DataSnapshot =
        suspendCancellableCoroutine { continuation ->
            addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    continuation.resume(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resumeWithException(error.toException())
                }
            })
        }

    private suspend fun com.google.firebase.database.DatabaseReference.setValueOnce(value: Any?): Unit =
        suspendCancellableCoroutine { continuation ->
            setValue(value)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    private companion object {
        const val TAG = "InterveningRepository"
    }
}
