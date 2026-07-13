package com.wim4you.intervene.distressstream

import android.content.Context
import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.SecureLog
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DistressStreamFirebase {
    private val database = FirebaseDatabaseProvider.reference()
    private val storage = FirebaseStorage.getInstance()

    suspend fun markStreamActive(distressUid: String, alias: String?) {
        val meta = mapOf(
            "active" to true,
            "alias" to (alias ?: ""),
            "startedAt" to System.currentTimeMillis(),
        )
        database.child(DistressStreamConstants.RTDB_ROOT)
            .child(distressUid)
            .child("meta")
            .setValue(meta)
            .await()
    }

    suspend fun deactivateStream(distressUid: String) {
        database.child(DistressStreamConstants.RTDB_ROOT)
            .child(distressUid)
            .child("meta")
            .child("active")
            .setValue(false)
            .await()
    }

    suspend fun uploadSegment(distressUid: String, file: File): DistressStreamSegment? {
        if (!file.exists() || file.length() == 0L) {
            SecureLog.w("DistressStreamFirebase", "Skipping empty distress segment upload for $distressUid")
            return null
        }
        val uploadedAt = System.currentTimeMillis()
        val segmentId = "seg_$uploadedAt"
        val storagePath = buildStoragePath(distressUid, uploadedAt, segmentId)
        val storageRef = storage.reference.child(storagePath)
        try {
            storageRef.putFile(Uri.fromFile(file)).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            val segmentMap = mapOf(
                "storagePath" to storagePath,
                "downloadUrl" to downloadUrl,
                "uploadedAt" to uploadedAt,
                "sizeBytes" to file.length(),
            )
            database.child(DistressStreamConstants.RTDB_ROOT)
                .child(distressUid)
                .child("segments")
                .child(segmentId)
                .setValue(segmentMap)
                .await()
            SecureLog.i("DistressStreamFirebase", "Uploaded distress stream segment $segmentId to $storagePath")
            return DistressStreamSegment(
                segmentId = segmentId,
                downloadUrl = downloadUrl,
                uploadedAtMillis = uploadedAt,
            )
        } catch (exception: Exception) {
            SecureLog.e("DistressStreamFirebase", "Failed to upload distress segment $segmentId for $distressUid", exception)
            throw exception
        }
    }

    fun buildStoragePath(userId: String, uploadedAtMillis: Long, segmentId: String): String {
        val dateFolder = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(uploadedAtMillis))
        return "${DistressStreamConstants.STORAGE_ROOT}/$userId/$dateFolder/$segmentId.mp4"
    }

    suspend fun registerViewer(distressUid: String, patrolUid: String) {
        val viewerMap = mapOf(
            "registeredAt" to System.currentTimeMillis(),
        )
        database.child(DistressStreamConstants.RTDB_ROOT)
            .child(distressUid)
            .child("viewers")
            .child(patrolUid)
            .setValue(viewerMap)
            .await()
    }

    suspend fun downloadSegmentToFile(
        storagePath: String?,
        downloadUrl: String?,
        destination: File,
    ) {
        destination.parentFile?.mkdirs()
        if (!storagePath.isNullOrBlank()) {
            try {
                storage.reference.child(storagePath).getFile(destination).await()
                return
            } catch (exception: Exception) {
                SecureLog.e("DistressStreamFirebase", "Storage SDK download failed for $storagePath, falling back to URL", exception)
            }
        }
        if (downloadUrl.isNullOrBlank()) {
            throw IllegalStateException("No download path available for distress segment")
        }
        URL(downloadUrl).openStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}

object DistressRecordingLocalStore {
    fun sessionDirectory(context: Context, sessionPath: String): File {
        return com.wim4you.intervene.recording.RecordingLocalStore.sessionDirectory(context, sessionPath)
    }

    fun createSessionPath(distressAlias: String?): String {
        val username = distressAlias?.ifBlank { null } ?: "unknown"
        return com.wim4you.intervene.recording.RecordingLocalStore.createSessionPath(username)
    }

    fun segmentFile(context: Context, sessionPath: String, segmentId: String): File {
        return com.wim4you.intervene.recording.RecordingLocalStore.segmentFile(context, sessionPath, segmentId)
    }

    fun saveSessionMetadata(
        context: Context,
        sessionPath: String,
        distressId: String,
        distressAlias: String?,
        startedAtMillis: Long,
    ) {
        com.wim4you.intervene.recording.RecordingLocalStore.saveSessionMetadata(
            context = context,
            sessionPath = sessionPath,
            distressId = distressId,
            distressAlias = distressAlias,
            startedAtMillis = startedAtMillis,
        )
    }

    fun listSessions(context: Context): List<DistressRecordingSession> {
        return com.wim4you.intervene.recording.RecordingLocalStore.listAll(context)
            .filterIsInstance<com.wim4you.intervene.recording.RecordingListItem.DistressSession>()
            .map { item ->
                DistressRecordingSession(
                    sessionId = item.sessionPath,
                    distressId = "",
                    distressAlias = item.username,
                    startedAtMillis = item.startedAtMillis,
                    segmentCount = item.segmentCount,
                )
            }
    }

    fun listSegments(context: Context, sessionPath: String): List<File> {
        return com.wim4you.intervene.recording.RecordingLocalStore.listSegments(context, sessionPath)
    }

    fun deleteSession(context: Context, session: DistressRecordingSession): Boolean {
        return com.wim4you.intervene.recording.RecordingLocalStore.deleteItem(
            context,
            com.wim4you.intervene.recording.RecordingListItem.DistressSession(
                sessionPath = session.sessionId,
                username = session.distressAlias ?: "",
                startedAtMillis = session.startedAtMillis,
                segmentCount = session.segmentCount,
            ),
        )
    }

    fun formatTimestamp(millis: Long) =
        com.wim4you.intervene.recording.RecordingLocalStore.formatTimestamp(millis)

    fun formatDuration(durationMillis: Long?) =
        com.wim4you.intervene.recording.RecordingLocalStore.formatDuration(durationMillis)
}

object DistressStreamController {
    suspend fun ensurePublisherReady(context: Context, alias: String?) {
        val distressUid = FirebaseAuthManager.ensureSignedIn()
        DistressStreamFirebase.markStreamActive(distressUid, alias)
    }

    suspend fun stopPublisher(context: Context) {
        val distressUid = try {
            FirebaseAuthManager.ensureSignedIn()
        } catch (_: Exception) {
            return
        }
        DistressStreamFirebase.deactivateStream(distressUid)
    }
}
