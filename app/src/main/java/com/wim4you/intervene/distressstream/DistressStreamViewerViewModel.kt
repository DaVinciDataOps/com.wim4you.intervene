package com.wim4you.intervene.distressstream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.SecureLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@HiltViewModel
class DistressStreamViewerViewModel @Inject constructor() : ViewModel() {
    private val database = FirebaseDatabaseProvider.reference()
    private var segmentsListener: ChildEventListener? = null
    private var metaListener: ValueEventListener? = null
    private var sessionId: String? = null
    private var distressRefPath: String? = null
    private var latestUploadedAt = 0L

    private val _streamActive = MutableStateFlow(false)
    val streamActive: StateFlow<Boolean> = _streamActive.asStateFlow()

    private val _currentVideoFile = MutableStateFlow<File?>(null)
    val currentVideoFile: StateFlow<File?> = _currentVideoFile.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _streamExpired = MutableStateFlow(false)
    val streamExpired: StateFlow<Boolean> = _streamExpired.asStateFlow()

    private val _lastModifiedAt = MutableStateFlow<Long?>(null)
    val lastModifiedAt: StateFlow<Long?> = _lastModifiedAt.asStateFlow()

    fun startWatching(
        context: android.content.Context,
        distressId: String,
        distressAlias: String?,
    ) {
        val startedAtMillis = System.currentTimeMillis()
        sessionId = DistressRecordingLocalStore.createSessionPath(distressAlias)
        DistressRecordingLocalStore.saveSessionMetadata(
            context = context,
            sessionPath = sessionId!!,
            distressId = distressId,
            distressAlias = distressAlias,
            startedAtMillis = startedAtMillis,
        )

        val distressRef = database.child(DistressStreamConstants.RTDB_ROOT).child(distressId)
        distressRefPath = distressId
        latestUploadedAt = 0L
        _streamExpired.value = false
        _lastModifiedAt.value = null

        viewModelScope.launch {
            registerViewerBestEffort(distressId)
            loadLatestSegment(context, distressRef)
        }

        metaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("meta").child("active").getValue(Boolean::class.java) ?: false
                _streamActive.value = active
            }

            override fun onCancelled(error: DatabaseError) {
                _statusMessage.value = error.message
            }
        }
        distressRef.addValueEventListener(metaListener!!)

        segmentsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                considerSegmentSnapshot(context, snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                considerSegmentSnapshot(context, snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                _statusMessage.value = error.message
            }
        }
        distressRef.child("segments").addChildEventListener(segmentsListener!!)
    }

    fun onSegmentPlaybackCompleted() {
        _currentVideoFile.value = null
    }

    fun stopWatching() {
        val distressId = distressRefPath
        if (distressId != null) {
            segmentsListener?.let { listener ->
                database.child(DistressStreamConstants.RTDB_ROOT)
                    .child(distressId)
                    .child("segments")
                    .removeEventListener(listener)
            }
            metaListener?.let { listener ->
                database.child(DistressStreamConstants.RTDB_ROOT)
                    .child(distressId)
                    .removeEventListener(listener)
            }
        }
        segmentsListener = null
        metaListener = null
        distressRefPath = null
        latestUploadedAt = 0L
        _lastModifiedAt.value = null
    }

    suspend fun verifyViewerAccess(distressId: String): Boolean {
        val patrolUid = try {
            FirebaseAuthManager.ensureSignedIn()
        } catch (_: Exception) {
            return false
        }
        if (hasIntervention(distressId, patrolUid)) {
            return true
        }
        val snapshot = database.child(DistressStreamConstants.RTDB_ROOT)
            .child(distressId)
            .child("viewers")
            .child(patrolUid)
            .getOnce()
        return snapshot.exists()
    }

    suspend fun hasIntervention(distressId: String, patrolUid: String): Boolean {
        val snapshot = database.child("intervening")
            .child(distressId)
            .child(patrolUid)
            .getOnce()
        return snapshot.exists()
    }

    private suspend fun registerViewerBestEffort(distressId: String) {
        try {
            val patrolUid = FirebaseAuthManager.ensureSignedIn()
            DistressStreamFirebase.registerViewer(distressId, patrolUid)
        } catch (exception: Exception) {
            SecureLog.e("DistressStreamViewer", "Could not register stream viewer", exception)
        }
    }

    private suspend fun loadLatestSegment(
        context: android.content.Context,
        distressRef: com.google.firebase.database.DatabaseReference,
    ) {
        try {
            val snapshot = distressRef.child("segments").getOnce()
            val latest = snapshot.children
                .mapNotNull { child -> segmentFromSnapshot(child) }
                .maxByOrNull { it.uploadedAt }
            if (latest == null) return
            considerSegment(context, latest)
        } catch (exception: Exception) {
            SecureLog.e("DistressStreamViewer", "Failed to load distress segments", exception)
            _statusMessage.value = exception.message
        }
    }

    private fun considerSegmentSnapshot(context: android.content.Context, snapshot: DataSnapshot) {
        val segment = segmentFromSnapshot(snapshot) ?: return
        considerSegment(context, segment)
    }

    private fun considerSegment(context: android.content.Context, segment: SegmentRef) {
        if (segment.uploadedAt < latestUploadedAt) return
        latestUploadedAt = segment.uploadedAt
        _lastModifiedAt.value = segment.uploadedAt

        if (!isSegmentFresh(segment.uploadedAt)) {
            markStreamExpired()
            return
        }

        _streamExpired.value = false
        downloadAndPlayLatest(context, segment)
    }

    private fun downloadAndPlayLatest(context: android.content.Context, segment: SegmentRef) {
        val session = sessionId ?: return
        viewModelScope.launch {
            try {
                val destination = DistressRecordingLocalStore.segmentFile(context, session, segment.segmentId)
                DistressStreamFirebase.downloadSegmentToFile(
                    storagePath = segment.storagePath,
                    downloadUrl = segment.downloadUrl,
                    destination = destination,
                )
                _currentVideoFile.value = destination
                _statusMessage.value = null
            } catch (exception: Exception) {
                SecureLog.e("DistressStreamViewer", "Failed to download distress segment ${segment.segmentId}", exception)
                _statusMessage.value = exception.message
            }
        }
    }

    private fun markStreamExpired() {
        _streamExpired.value = true
        _currentVideoFile.value = null
    }

    private fun isSegmentFresh(uploadedAt: Long): Boolean {
        return System.currentTimeMillis() - uploadedAt <= DistressStreamConstants.MAX_LIVE_SEGMENT_AGE_MS
    }

    private fun segmentFromSnapshot(snapshot: DataSnapshot): SegmentRef? {
        val segmentId = snapshot.key ?: return null
        val uploadedAt = snapshot.child("uploadedAt").getValue(Long::class.java) ?: return null
        val storagePath = snapshot.child("storagePath").getValue(String::class.java)
        val downloadUrl = snapshot.child("downloadUrl").getValue(String::class.java)
        if (storagePath.isNullOrBlank() && downloadUrl.isNullOrBlank()) return null
        return SegmentRef(
            segmentId = segmentId,
            uploadedAt = uploadedAt,
            storagePath = storagePath,
            downloadUrl = downloadUrl,
        )
    }

    private data class SegmentRef(
        val segmentId: String,
        val uploadedAt: Long,
        val storagePath: String?,
        val downloadUrl: String?,
    )

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
}
