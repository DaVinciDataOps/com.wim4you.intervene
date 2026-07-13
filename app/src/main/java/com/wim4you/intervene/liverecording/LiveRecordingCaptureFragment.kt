package com.wim4you.intervene.liverecording

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.R
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.databinding.FragmentLiveRecordingCaptureBinding
import com.wim4you.intervene.distressstream.DistressStreamController
import com.wim4you.intervene.distressstream.webrtc.DistressWebRtcPublisher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LiveRecordingCaptureFragment : Fragment(), LiveRecordingCameraManager.Listener {
    private var _binding: FragmentLiveRecordingCaptureBinding? = null
    private val binding get() = _binding!!
    private var cameraManager: LiveRecordingCameraManager? = null
    private var webRtcPublisher: DistressWebRtcPublisher? = null
    private var closing = false
    private val maxDurationHandler = Handler(Looper.getMainLooper())
    private val distressStreamingEnabled: Boolean
        get() = AppModeController.isDistressActive

    private val maxDurationRunnable = Runnable {
        if (!closing) {
            stopAndClose()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveRecordingCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!AppModeController.isGuidedTrip) {
            Toast.makeText(requireContext(), R.string.live_recording_guided_trip_required, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        if (!LiveRecordingPermissions.hasAllPermissions(requireActivity())) {
            Toast.makeText(requireContext(), R.string.live_recording_permission_required, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        binding.btnStopRecording.setOnClickListener { stopAndClose() }
        binding.btnSwitchCamera.setOnClickListener {
            if (distressStreamingEnabled) {
                webRtcPublisher?.switchCamera()
            } else {
                cameraManager?.switchCamera()
            }
        }
        binding.recordingIndicator.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveRecordingController.stopRequests.collectLatest {
                    stopAndClose()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AppModeController.isGuidedTrip || closing) return
        if (distressStreamingEnabled) {
            startWebRtcSession()
        } else {
            startCameraSession()
        }
    }

    override fun onDestroyView() {
        maxDurationHandler.removeCallbacks(maxDurationRunnable)
        if (!closing) {
            cameraManager?.stop()
            webRtcPublisher?.stop()
            LiveRecordingController.stopRecording(requireContext())
            if (distressStreamingEnabled) {
                viewLifecycleOwner.lifecycleScope.launch {
                    DistressStreamController.stopPublisher(requireContext())
                }
            }
        }
        cameraManager = null
        webRtcPublisher = null
        super.onDestroyView()
        _binding = null
    }

    private fun startCameraSession() {
        if (cameraManager != null) return

        binding.previewView.isVisible = true
        binding.webrtcPreviewView.isVisible = false

        LiveRecordingController.bindContext(requireContext())
        if (!LiveRecordingController.ensureForegroundService(requireContext())) {
            Toast.makeText(requireContext(), R.string.live_recording_start_failed, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        cameraManager = LiveRecordingCameraManager(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            previewView = binding.previewView,
            listener = this,
            streamSegments = false,
        )
        cameraManager?.start()
    }

    private fun startWebRtcSession() {
        if (webRtcPublisher != null) return

        binding.previewView.isVisible = false
        binding.webrtcPreviewView.isVisible = true

        LiveRecordingController.bindContext(requireContext())
        if (!LiveRecordingController.ensureForegroundService(requireContext())) {
            Toast.makeText(requireContext(), R.string.live_recording_start_failed, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val alias = AppModeController.person?.alias
                DistressStreamController.ensurePublisherReady(requireContext(), alias)
                val distressUid = FirebaseAuthManager.ensureSignedIn()
                val publisher = DistressWebRtcPublisher(
                    context = requireContext(),
                    distressUid = distressUid,
                    previewRenderer = binding.webrtcPreviewView,
                )
                publisher.setListener(object : DistressWebRtcPublisher.Listener {
                    override fun onPublisherStarted() {
                        if (_binding == null) return
                        binding.recordingIndicator.isVisible = true
                        LiveRecordingController.onRecordingStarted()
                        scheduleMaxDuration()
                    }

                    override fun onPublisherStopped() {
                        navigateBack()
                    }

                    override fun onError(message: String) {
                        if (!isAdded) return
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        stopAndClose()
                    }

                    override fun onViewerConnected(patrolUid: String) {
                        SecureLog.i("LiveRecordingCapture", "Patroller connected to distress stream: $patrolUid")
                    }

                    override fun onViewerDisconnected(patrolUid: String) {
                        SecureLog.i("LiveRecordingCapture", "Patroller disconnected from distress stream: $patrolUid")
                    }
                })
                webRtcPublisher = publisher
                publisher.start()
            } catch (exception: Exception) {
                SecureLog.e("LiveRecordingCapture", "Failed to start distress WebRTC stream", exception)
                Toast.makeText(requireContext(), R.string.distress_stream_start_failed, Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }
        }
    }

    private fun scheduleMaxDuration() {
        maxDurationHandler.removeCallbacks(maxDurationRunnable)
        maxDurationHandler.postDelayed(maxDurationRunnable, LiveRecordingConstants.MAX_RECORDING_DURATION_MS)
    }

    private fun stopAndClose() {
        if (closing) return
        closing = true
        maxDurationHandler.removeCallbacks(maxDurationRunnable)
        _binding?.recordingIndicator?.isVisible = false
        LiveRecordingController.stopRecording(requireContext())
        if (distressStreamingEnabled) {
            lifecycleScope.launch {
                DistressStreamController.stopPublisher(requireContext())
            }
            val publisher = webRtcPublisher
            if (publisher != null) {
                publisher.stop()
            } else {
                navigateBack()
            }
        } else {
            val manager = cameraManager
            if (manager != null) {
                manager.stop()
            } else {
                navigateBack()
            }
        }
    }

    private fun navigateBack() {
        cameraManager = null
        webRtcPublisher = null
        if (!isAdded) return
        if (findNavController().currentDestination?.id == R.id.nav_live_recording_capture) {
            findNavController().popBackStack()
        }
    }

    override fun onRecordingStarted() {
        if (_binding == null) return
        binding.recordingIndicator.isVisible = true
        LiveRecordingController.onRecordingStarted()
    }

    override fun onRecordingStopped() {
        navigateBack()
    }

    override fun onError(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        stopAndClose()
    }
}
