package com.wim4you.intervene.distressstream.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.ChildEventListener
import com.wim4you.intervene.SecureLog
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DistressWebRtcPublisher(
    private val context: Context,
    private val distressUid: String,
    private val previewRenderer: SurfaceViewRenderer,
) {
    interface Listener {
        fun onPublisherStarted()
        fun onPublisherStopped()
        fun onError(message: String)
        fun onViewerConnected(patrolUid: String)
        fun onViewerDisconnected(patrolUid: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private var listener: Listener? = null
    private var factoryHolder: WebRtcPeerConnectionFactoryHolder? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: Camera2Capturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var usingFrontCamera = false
    private var offersListener: ChildEventListener? = null
    private val peerConnections = ConcurrentHashMap<String, ViewerPeerConnection>()

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val holder = WebRtcPeerConnectionFactoryHolder(context)
            factoryHolder = holder
            initializePreviewRenderer(holder)
            startLocalTracks(holder)
            offersListener = WebRtcSignaling.observeOffers(distressUid) { patrolUid, offer ->
                handleViewerOffer(patrolUid, offer)
            }
            mainHandler.post { listener?.onPublisherStarted() }
        } catch (exception: Exception) {
            started.set(false)
            SecureLog.e(TAG, "Failed to start WebRTC publisher", exception)
            mainHandler.post { listener?.onError(exception.message ?: "WebRTC start failed") }
        }
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                usingFrontCamera = isFrontCamera
                mainHandler.post {
                    previewRenderer.setMirror(isFrontCamera)
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                SecureLog.e(TAG, "Camera switch failed: $errorDescription")
            }
        })
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        offersListener?.let { WebRtcSignaling.viewersRef(distressUid).removeEventListener(it) }
        offersListener = null
        peerConnections.values.forEach { it.release() }
        peerConnections.clear()
        WebRtcSignaling.clearAllSignaling(distressUid)
        stopLocalTracks()
        previewRenderer.release()
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        factoryHolder?.release()
        factoryHolder = null
        mainHandler.post { listener?.onPublisherStopped() }
    }

    private fun initializePreviewRenderer(holder: WebRtcPeerConnectionFactoryHolder) {
        previewRenderer.init(holder.eglBase.eglBaseContext, null)
        previewRenderer.setMirror(false)
        previewRenderer.setEnableHardwareScaler(true)
    }

    private fun startLocalTracks(holder: WebRtcPeerConnectionFactoryHolder) {
        val capturer = createCameraCapturer(frontFacing = false)
        videoCapturer = capturer
        usingFrontCamera = false
        surfaceTextureHelper = SurfaceTextureHelper.create("DistressCaptureThread", holder.eglBase.eglBaseContext)
        val source = holder.factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
        capturer.startCapture(WebRtcConfig.VIDEO_WIDTH, WebRtcConfig.VIDEO_HEIGHT, WebRtcConfig.VIDEO_FPS)

        localVideoTrack = holder.factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source)
        localVideoTrack?.addSink(previewRenderer)

        audioSource = holder.factory.createAudioSource(MediaConstraints())
        localAudioTrack = holder.factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, audioSource)
    }

    private fun stopLocalTracks() {
        try {
            videoCapturer?.stopCapture()
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to stop video capturer", exception)
        }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
    }

    private fun createCameraCapturer(frontFacing: Boolean): Camera2Capturer {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { device ->
            enumerator.isFrontFacing(device) == frontFacing
        } ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera available on this device")
        return Camera2Capturer(context, deviceName, null)
    }

    private fun handleViewerOffer(patrolUid: String, offer: SessionDescription) {
        val holder = factoryHolder ?: return
        val existing = peerConnections[patrolUid]
        if (existing != null && existing.handledOfferSdp == offer.description) {
            return
        }
        existing?.release()
        val viewerPeer = ViewerPeerConnection(patrolUid, holder)
        peerConnections[patrolUid] = viewerPeer
        viewerPeer.handleOffer(offer)
    }

    private inner class ViewerPeerConnection(
        private val patrolUid: String,
        private val holder: WebRtcPeerConnectionFactoryHolder,
    ) : PeerConnection.Observer, SdpObserver {
        var handledOfferSdp: String? = null
        private var peerConnection: PeerConnection? = null
        private var viewerIceListener: ChildEventListener? = null
        private var released = false

        fun handleOffer(offer: SessionDescription) {
            handledOfferSdp = offer.description
            val rtcConfig = PeerConnection.RTCConfiguration(WebRtcConfig.iceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            peerConnection = holder.factory.createPeerConnection(rtcConfig, this)
            val connection = peerConnection ?: return

            localAudioTrack?.let { connection.addTrack(it, listOf(STREAM_ID)) }
            localVideoTrack?.let { connection.addTrack(it, listOf(STREAM_ID)) }

            viewerIceListener = WebRtcSignaling.observeRemoteIceCandidates(
                distressUid = distressUid,
                patrolUid = patrolUid,
                node = WebRtcSignaling.NODE_VIEWER_ICE,
            ) { candidate ->
                connection.addIceCandidate(candidate)
            }

            connection.setRemoteDescription(this, offer)
        }

        override fun onCreateSuccess(description: SessionDescription?) {
            val connection = peerConnection ?: return
            val sessionDescription = description ?: return
            connection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(localDescription: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    WebRtcSignaling.sendAnswer(distressUid, patrolUid, sessionDescription)
                }

                override fun onCreateFailure(error: String?) {
                    reportError("Create local description failed: $error")
                }

                override fun onSetFailure(error: String?) {
                    reportError("Set local description failed: $error")
                }
            }, sessionDescription)
        }

        override fun onSetSuccess() {
            peerConnection?.createAnswer(this, MediaConstraints())
        }

        override fun onCreateFailure(error: String?) {
            reportError("Create answer failed: $error")
        }

        override fun onSetFailure(error: String?) {
            reportError("Set remote description failed: $error")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> mainHandler.post { listener?.onViewerConnected(patrolUid) }
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED,
                -> mainHandler.post { listener?.onViewerDisconnected(patrolUid) }
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
                node = WebRtcSignaling.NODE_PUBLISHER_ICE,
                candidate = iceCandidate,
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(dataChannel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit

        fun release() {
            if (released) return
            released = true
            viewerIceListener?.let { listener ->
                WebRtcSignaling.viewerRef(distressUid, patrolUid)
                    .child(WebRtcSignaling.NODE_VIEWER_ICE)
                    .removeEventListener(listener)
            }
            viewerIceListener = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            WebRtcSignaling.clearViewerSignaling(distressUid, patrolUid)
            mainHandler.post { listener?.onViewerDisconnected(patrolUid) }
        }

        private fun reportError(message: String) {
            SecureLog.e(TAG, "Viewer peer error for $patrolUid: $message")
            mainHandler.post { listener?.onError(message) }
        }
    }

    private companion object {
        const val TAG = "DistressWebRtcPublisher"
        const val STREAM_ID = "distress_stream"
        const val LOCAL_AUDIO_TRACK_ID = "distress_audio"
        const val LOCAL_VIDEO_TRACK_ID = "distress_video"
    }
}
