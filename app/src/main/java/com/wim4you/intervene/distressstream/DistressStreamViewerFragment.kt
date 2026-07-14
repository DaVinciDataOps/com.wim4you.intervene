package com.wim4you.intervene.distressstream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentDistressStreamViewerBinding
import com.wim4you.intervene.ui.map.MapDataViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DistressStreamViewerFragment : Fragment() {
    private val viewModel: DistressStreamViewerViewModel by viewModels()
    private val mapDataViewModel: MapDataViewModel by activityViewModels()
    private var _binding: FragmentDistressStreamViewerBinding? = null
    private val binding get() = _binding!!
    private var isRecording = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDistressStreamViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val distressId = requireArguments().getString(DistressStreamConstants.ARG_DISTRESS_ID)
        val distressAlias = requireArguments().getString(DistressStreamConstants.ARG_DISTRESS_ALIAS)
        if (distressId.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.distress_stream_not_available, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        binding.streamTitle.text = getString(
            R.string.distress_stream_viewer_title,
            distressAlias.orEmpty().ifBlank { getString(R.string.distress_stream_unknown_person) },
        )

        binding.remoteVideoView.isVisible = true
        binding.videoPlayer.isVisible = false
        binding.streamStatus.isVisible = true
        binding.streamStatus.text = getString(R.string.distress_stream_connecting)
        binding.streamRecordControls.isVisible = false
        binding.btnStartStreamRecording.isEnabled = false
        binding.btnStopStreamRecording.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val allowed = viewModel.verifyViewerAccess(distressId) ||
                mapDataViewModel.isIntervening(distressId)
            if (!allowed) {
                Toast.makeText(requireContext(), R.string.distress_stream_access_denied, Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
                return@launch
            }
            viewModel.startWatching(requireContext(), distressId, distressAlias, binding.remoteVideoView)
        }

        binding.btnStartStreamRecording.setOnClickListener {
            if (!isRecording) startLocalRecording(distressAlias)
        }
        binding.btnStopStreamRecording.setOnClickListener {
            if (isRecording) stopLocalRecording()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.streamActive.collectLatest { active ->
                        binding.streamLiveIndicator.isVisible = active && viewModel.connectionEstablished.value
                    }
                }
                launch {
                    viewModel.connectionEstablished.collectLatest { connected ->
                        binding.streamLiveIndicator.isVisible =
                            connected && viewModel.streamActive.value && viewModel.videoReady.value
                        if (connected) {
                            binding.streamStatus.isVisible = false
                            binding.streamRecordControls.isVisible = true
                        }
                    }
                }
                launch {
                    viewModel.videoReady.collectLatest { ready ->
                        binding.streamLiveIndicator.isVisible =
                            ready && viewModel.connectionEstablished.value && viewModel.streamActive.value
                        binding.btnStartStreamRecording.isEnabled = ready && !isRecording
                        if (ready) {
                            binding.streamRecordControls.isVisible = true
                        }
                    }
                }
                launch {
                    viewModel.streamExpired.collectLatest { expired ->
                        if (expired) {
                            binding.streamLiveIndicator.isVisible = false
                            binding.streamStatus.text = getString(R.string.distress_stream_expired)
                            binding.streamStatus.isVisible = true
                            binding.streamRecordControls.isVisible = false
                            stopLocalRecording()
                        }
                    }
                }
                launch {
                    viewModel.statusMessage.collectLatest { message ->
                        if (!message.isNullOrBlank() && !viewModel.connectionEstablished.value) {
                            binding.streamStatus.text = message
                            binding.streamStatus.isVisible = true
                        }
                    }
                }
            }
        }
    }

    private fun startLocalRecording(distressAlias: String?) {
        val started = viewModel.startRecording(requireContext(), distressAlias)
        if (!started) {
            Toast.makeText(requireContext(), R.string.distress_stream_record_failed, Toast.LENGTH_SHORT).show()
            return
        }
        isRecording = true
        binding.btnStartStreamRecording.isEnabled = false
        binding.btnStopStreamRecording.isEnabled = true
    }

    private fun stopLocalRecording() {
        if (!isRecording) return
        isRecording = false
        viewModel.stopRecording()
        if (_binding != null) {
            binding.btnStartStreamRecording.isEnabled = viewModel.videoReady.value
            binding.btnStopStreamRecording.isEnabled = false
        }
    }

    override fun onDestroyView() {
        stopLocalRecording()
        viewModel.stopWatching()
        super.onDestroyView()
        _binding = null
    }
}
