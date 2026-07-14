package com.wim4you.intervene.liverecording

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentLiveRecordingPlayerBinding
import com.wim4you.intervene.recording.PublicVideoStore
import com.wim4you.intervene.recording.RecordingFileResolver
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LiveRecordingPlayerFragment : Fragment() {
    private var _binding: FragmentLiveRecordingPlayerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveRecordingPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filename = requireArguments().getString(LiveRecordingConstants.ARG_RECORDING_FILENAME)
        if (filename.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.live_recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        val file = RecordingFileResolver.resolve(requireContext(), filename)
        if (file == null) {
            Toast.makeText(requireContext(), R.string.live_recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        binding.recordingTitle.text = LiveRecordingLocalStore.formatTimestamp(file.lastModified())
        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(binding.videoPlayer)
        binding.videoPlayer.setMediaController(mediaController)
        binding.videoPlayer.setVideoURI(Uri.fromFile(file))
        binding.videoPlayer.setOnPreparedListener { player ->
            player.isLooping = false
            binding.videoPlayer.start()
        }

        binding.btnDeleteRecording.setOnClickListener {
            val entry = LiveRecordingEntry(
                id = file.nameWithoutExtension,
                filename = filename,
                createdAtMillis = file.lastModified(),
                durationMillis = null,
            )
            LiveRecordingDeletePrompt.show(requireContext()) {
                if (PublicVideoStore.isPublicPath(filename)) {
                    PublicVideoStore.delete(filename)
                } else {
                    LiveRecordingLocalStore.deleteRecording(requireContext(), entry)
                }
                findNavController().popBackStack()
            }
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
