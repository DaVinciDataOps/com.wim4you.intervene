package com.wim4you.intervene.distressstream

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
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
import com.wim4you.intervene.SecureLog
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
    private var currentPlayingFile: java.io.File? = null

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

        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(binding.videoPlayer)
        binding.videoPlayer.setMediaController(mediaController)
        binding.videoPlayer.setOnCompletionListener {
            viewModel.onSegmentPlaybackCompleted()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val allowed = viewModel.verifyViewerAccess(distressId) ||
                mapDataViewModel.isIntervening(distressId)
            if (!allowed) {
                Toast.makeText(requireContext(), R.string.distress_stream_access_denied, Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
                return@launch
            }
            viewModel.startWatching(requireContext(), distressId, distressAlias)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.streamActive.collectLatest { active ->
                        binding.streamLiveIndicator.isVisible = active
                    }
                }
                launch {
                    viewModel.streamExpired.collectLatest { expired ->
                        if (expired) {
                            binding.streamLiveIndicator.isVisible = false
                            binding.streamStatus.text = getString(R.string.distress_stream_expired)
                            binding.streamStatus.isVisible = true
                            binding.videoPlayer.stopPlayback()
                            currentPlayingFile = null
                        }
                    }
                }
                launch {
                    viewModel.currentVideoFile.collectLatest { file ->
                        if (!viewModel.streamExpired.value) {
                            playFile(file)
                        }
                    }
                }
                launch {
                    viewModel.lastModifiedAt.collectLatest { millis ->
                        if (millis != null) {
                            binding.streamLastModified.text = getString(
                                R.string.distress_stream_last_modified,
                                DistressRecordingLocalStore.formatTimestamp(millis),
                            )
                            binding.streamLastModified.isVisible = true
                        } else {
                            binding.streamLastModified.isVisible = false
                        }
                    }
                }
                launch {
                    viewModel.statusMessage.collectLatest { message ->
                        if (!message.isNullOrBlank()) {
                            binding.streamStatus.text = message
                            binding.streamStatus.isVisible = true
                        }
                    }
                }
            }
        }
    }

    private fun playFile(file: java.io.File?) {
        if (file == null || !file.exists()) return
        if (currentPlayingFile?.absolutePath == file.absolutePath) return
        currentPlayingFile = file
        binding.streamStatus.isVisible = false
        binding.videoPlayer.setVideoURI(Uri.fromFile(file))
        binding.videoPlayer.setOnPreparedListener { player ->
            player.isLooping = false
            binding.videoPlayer.start()
        }
        binding.videoPlayer.setOnErrorListener { _, what, extra ->
            binding.streamStatus.text = getString(R.string.distress_stream_playback_failed)
            binding.streamStatus.isVisible = true
            SecureLog.e("DistressStreamViewer", "Video playback error what=$what extra=$extra")
            true
        }
    }

    override fun onDestroyView() {
        viewModel.stopWatching()
        binding.videoPlayer.stopPlayback()
        super.onDestroyView()
        _binding = null
    }
}
