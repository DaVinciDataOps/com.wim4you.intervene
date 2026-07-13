package com.wim4you.intervene.liverecording

import android.os.Bundle
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
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentLiveRecordingCaptureBinding
import com.wim4you.intervene.distressstream.DistressStreamController
import com.wim4you.intervene.distressstream.DistressStreamFirebase
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.SecureLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class LiveRecordingCaptureFragment : Fragment(), LiveRecordingCameraManager.Listener {
    private var _binding: FragmentLiveRecordingCaptureBinding? = null
    private val binding get() = _binding!!
    private var cameraManager: LiveRecordingCameraManager? = null
    private var closing = false
    private val distressStreamingEnabled: Boolean
        get() = AppModeController.isDistressActive

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
        binding.btnSwitchCamera.setOnClickListener { cameraManager?.switchCamera() }
        binding.recordingIndicator.isVisible = false

        if (distressStreamingEnabled) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val alias = AppModeController.person?.alias
                    DistressStreamController.ensurePublisherReady(requireContext(), alias)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), R.string.distress_stream_start_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

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
        startCameraSession()
    }

    override fun onDestroyView() {
        if (!closing) {
            cameraManager?.stop()
            LiveRecordingController.stopRecording(requireContext())
            if (distressStreamingEnabled) {
                viewLifecycleOwner.lifecycleScope.launch {
                    DistressStreamController.stopPublisher(requireContext())
                }
            }
        }
        cameraManager = null
        super.onDestroyView()
        _binding = null
    }

    private fun startCameraSession() {
        if (cameraManager != null) return

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
            streamSegments = distressStreamingEnabled,
            onSegmentFinalized = { file -> uploadDistressSegment(file) },
        )
        cameraManager?.start()
    }

    private fun uploadDistressSegment(file: java.io.File) {
        if (closing || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val distressUid = FirebaseAuthManager.ensureSignedIn()
                    DistressStreamFirebase.uploadSegment(distressUid, file)
                } catch (exception: Exception) {
                    SecureLog.e("LiveRecordingCapture", "Failed to upload distress stream segment", exception)
                }
            }
        }
    }

    private fun stopAndClose() {
        if (closing) return
        closing = true
        _binding?.recordingIndicator?.isVisible = false
        LiveRecordingController.stopRecording(requireContext())
        if (distressStreamingEnabled) {
            lifecycleScope.launch {
                DistressStreamController.stopPublisher(requireContext())
            }
        }
        val manager = cameraManager
        if (manager != null) {
            manager.stop()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        cameraManager = null
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
