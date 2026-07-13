package com.wim4you.intervene.distressstream

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentDistressRecordingPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class DistressRecordingPlayerFragment : Fragment() {
    private var _binding: FragmentDistressRecordingPlayerBinding? = null
    private val binding get() = _binding!!
    private var segmentFiles: List<File> = emptyList()
    private var segmentIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDistressRecordingPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sessionId = requireArguments().getString(DistressStreamConstants.ARG_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.distress_recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        segmentFiles = DistressRecordingLocalStore.listSegments(requireContext(), sessionId)
        if (segmentFiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.distress_recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(binding.videoPlayer)
        binding.videoPlayer.setMediaController(mediaController)
        binding.videoPlayer.setOnCompletionListener {
            playNextSegment()
        }
        playSegmentAt(0)
    }

    private fun playSegmentAt(index: Int) {
        if (index >= segmentFiles.size) return
        segmentIndex = index
        val file = segmentFiles[index]
        binding.recordingTitle.text = getString(
            R.string.distress_recording_player_clip,
            index + 1,
            segmentFiles.size,
        )
        binding.videoPlayer.setVideoURI(Uri.fromFile(file))
        binding.videoPlayer.setOnPreparedListener { player ->
            player.isLooping = false
            binding.videoPlayer.start()
        }
    }

    private fun playNextSegment() {
        if (segmentIndex + 1 < segmentFiles.size) {
            playSegmentAt(segmentIndex + 1)
        }
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            binding.videoPlayer.pause()
        }
    }

    override fun onDestroyView() {
        binding.videoPlayer.stopPlayback()
        super.onDestroyView()
        _binding = null
    }
}
