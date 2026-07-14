package com.wim4you.intervene.distressstream.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.SecureLog
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

class DistressWebRtcViewer(
    private val context: Context,
    private val distressUid: String,
    private val patrolUid: String,
    private val remoteRenderer: SurfaceViewRenderer,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onVideoReady()
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private var listener: Listener? = null
    private var factoryHolder: WebRtcPeerConnectionFactoryHolder? = null
    private var peerConnection: PeerConnection? = null
    private var answerListener: ValueEventListener? = null
    private var publisherIceListener: ChildEventListener? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var streamRecorder: WebRtcStreamMp4Recorder? = null
    private var answerHandled = false

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val holder = WebRtcPeerConnectionFactoryHolder(context)
            factoryHolder = holder
            initializeRenderer(holder)
            createPeerConnection(holder)
            createAndSendOffer()
        } catch (exception: Exception) {
            started.set(false)
            SecureLog.e(TAG, "Failed to start WebRTC viewer", exception)
            mainHandler.post { listener?.onError(exception.message ?: "WebRTC viewer start failed") }
        }
    }

    fun isVideoReady(): Boolean = remoteVideoTrack?.enabled() == true

    fun startRecording(outputFile: java.io.File): Boolean {
        val track = remoteVideoTrack ?: return false
        if (!track.enabled()) return false
        if (streamRecorder != null) return false
        return try {
            val recorder = WebRtcStreamMp4Recorder(track)
            recorder.start(outputFile)
            streamRecorder = recorder
            true
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to start local stream recording", exception)
            false
        }
    }

    fun stopRecording() {
        streamRecorder?.stop()
        streamRecorder = null
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        stopRecording()
        answerListener?.let { listener ->
            WebRtcSignaling.viewerRef(distressUid, patrolUid)
                .child(WebRtcSignaling.NODE_ANSWER)
                .removeEventListener(listener)
        }
        answerListener = null
        publisherIceListener?.let { listener ->
            WebRtcSignaling.viewerRef(distressUid, patrolUid)
                .child(WebRtcSignaling.NODE_PUBLISHER_ICE)
                .removeEventListener(listener)
        }
        publisherIceListener = null
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteRenderer.release()
        factoryHolder?.release()
        factoryHolder = null
        WebRtcSignaling.clearViewerSignaling(distressUid, patrolUid)
        mainHandler.post { listener?.onDisconnected() }
    }

    private fun initializeRenderer(holder: WebRtcPeerConnectionFactoryHolder) {
        remoteRenderer.init(holder.eglBase.eglBaseContext, null)
        remoteRenderer.setMirror(false)
        remoteRenderer.setEnableHardwareScaler(true)
    }

    private fun createPeerConnection(holder: WebRtcPeerConnectionFactoryHolder) {
        val rtcConfig = PeerConnection.RTCConfiguration(WebRtcConfig.iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = holder.factory.createPeerConnection(rtcConfig, peerConnectionObserver)
        val connection = peerConnection ?: throw IllegalStateException("PeerConnection not created")

        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        publisherIceListener = WebRtcSignaling.observeRemoteIceCandidates(
            distressUid = distressUid,
            patrolUid = patrolUid,
            node = WebRtcSignaling.NODE_PUBLISHER_ICE,
        ) { candidate ->
            connection.addIceCandidate(candidate)
        }

        answerListener = WebRtcSignaling.observeAnswer(distressUid, patrolUid) { answer ->
            if (answerHandled) return@observeAnswer
            answerHandled = true
            connection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    reportError("Set answer failed to create: $error")
                }

                override fun onSetFailure(error: String?) {
                    reportError("Set answer failed: $error")
                }
            }, answer)
        }
    }

    private fun createAndSendOffer() {
        val connection = peerConnection ?: return
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                val offer = sessionDescription ?: return
                connection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(localDescription: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        WebRtcSignaling.sendOffer(distressUid, patrolUid, offer)
                    }

                    override fun onCreateFailure(error: String?) {
                        reportError("Set offer failed to create: $error")
                    }

                    override fun onSetFailure(error: String?) {
                        reportError("Set offer failed: $error")
                    }
                }, offer)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                reportError("Create offer failed: $error")
            }

            override fun onSetFailure(error: String?) {
                reportError("Unexpected set failure while creating offer: $error")
            }
        }, MediaConstraints())
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> mainHandler.post { listener?.onConnected() }
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED,
                -> mainHandler.post { listener?.onDisconnected() }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            val iceCandidate = candidate ?: return
            WebRtcSignaling.sendIceCandidate(
                distressUid = distressUid,
                patrolUid = patrolUid,
                node = WebRtcSignaling.NODE_VIEWER_ICE,
                candidate = iceCandidate,
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(dataChannel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack ?: return
            mainHandler.post {
                remoteVideoTrack?.removeSink(remoteRenderer)
                remoteVideoTrack = track
                track.addSink(remoteRenderer)
                listener?.onVideoReady()
            }
        }
    }

    private fun reportError(message: String) {
        SecureLog.e(TAG, message)
        mainHandler.post { listener?.onError(message) }
    }

    private companion object {
        const val TAG = "DistressWebRtcViewer"
    }
}
