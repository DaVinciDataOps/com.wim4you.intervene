package com.wim4you.intervene.distressstream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.distressstream.webrtc.DistressWebRtcViewer
import com.wim4you.intervene.recording.RecordingLocalStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@HiltViewModel
class DistressStreamViewerViewModel @Inject constructor() : ViewModel() {
    private val database = FirebaseDatabaseProvider.reference()
    private var metaListener: ValueEventListener? = null
    private var distressRefPath: String? = null
    private var webRtcViewer: DistressWebRtcViewer? = null

    private val _streamActive = MutableStateFlow(false)
    val streamActive: StateFlow<Boolean> = _streamActive.asStateFlow()

    private val _connectionEstablished = MutableStateFlow(false)
    val connectionEstablished: StateFlow<Boolean> = _connectionEstablished.asStateFlow()

    private val _videoReady = MutableStateFlow(false)
    val videoReady: StateFlow<Boolean> = _videoReady.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _streamExpired = MutableStateFlow(false)
    val streamExpired: StateFlow<Boolean> = _streamExpired.asStateFlow()

    fun startWatching(
        context: android.content.Context,
        distressId: String,
        distressAlias: String?,
        remoteRenderer: SurfaceViewRenderer,
    ) {
        val distressRef = database.child(DistressStreamConstants.RTDB_ROOT).child(distressId)
        distressRefPath = distressId
        _streamExpired.value = false
        _connectionEstablished.value = false
        _videoReady.value = false

        viewModelScope.launch {
            registerViewerBestEffort(distressId)
            startWebRtcViewer(context, distressId, remoteRenderer)
        }

        metaListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val active = snapshot.child("meta").child("active").getValue(Boolean::class.java) ?: false
                val startedAt = snapshot.child("meta").child("startedAt").getValue(Long::class.java) ?: 0L
                _streamActive.value = active
                if (!active && startedAt > 0L) {
                    _streamExpired.value = true
                } else if (active) {
                    _streamExpired.value = false
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                _statusMessage.value = error.message
            }
        }
        distressRef.addValueEventListener(metaListener!!)
    }

    fun startRecording(context: android.content.Context, distressAlias: String?): Boolean {
        if (webRtcViewer?.isVideoReady() != true) {
            SecureLog.w("DistressStreamViewer", "Cannot start recording: video track not ready")
            return false
        }
        val username = RecordingLocalStore.sanitizeUsername(distressAlias?.ifBlank { null } ?: "unknown")
        val outputFile = RecordingLocalStore.createRecordingFile(context, username)
        val started = webRtcViewer?.startRecording(outputFile) == true
        if (started) {
            SecureLog.i("DistressStreamViewer", "Recording live stream to ${outputFile.absolutePath}")
        }
        return started
    }

    fun stopRecording() {
        webRtcViewer?.stopRecording()
    }

    fun stopWatching() {
        stopRecording()
        webRtcViewer?.stop()
        webRtcViewer = null
        val distressId = distressRefPath
        if (distressId != null) {
            metaListener?.let { listener ->
                database.child(DistressStreamConstants.RTDB_ROOT)
                    .child(distressId)
                    .removeEventListener(listener)
            }
        }
        metaListener = null
        distressRefPath = null
        _connectionEstablished.value = false
        _videoReady.value = false
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

    private suspend fun startWebRtcViewer(
        context: android.content.Context,
        distressId: String,
        remoteRenderer: SurfaceViewRenderer,
    ) {
        val patrolUid = FirebaseAuthManager.ensureSignedIn()
        val viewer = DistressWebRtcViewer(
            context = context.applicationContext,
            distressUid = distressId,
            patrolUid = patrolUid,
            remoteRenderer = remoteRenderer,
        )
        viewer.setListener(object : DistressWebRtcViewer.Listener {
            override fun onConnected() {
                _connectionEstablished.value = true
                _statusMessage.value = null
            }

            override fun onDisconnected() {
                _connectionEstablished.value = false
                _videoReady.value = false
            }

            override fun onVideoReady() {
                _videoReady.value = true
            }

            override fun onError(message: String) {
                _statusMessage.value = message
            }
        })
        webRtcViewer = viewer
        viewer.start()
    }

    private suspend fun com.google.firebase.database.DatabaseReference.getOnce(): com.google.firebase.database.DataSnapshot =
        suspendCancellableCoroutine { continuation ->
            addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    continuation.resume(snapshot)
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    continuation.resumeWithException(error.toException())
                }
            })
        }
}
