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
import com.wim4you.intervene.ui.video.VideoDisplaySize
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

        val uri = RecordingFileResolver.resolveUri(requireContext(), filename)
        if (uri == null) {
            Toast.makeText(requireContext(), R.string.live_recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        binding.recordingTitle.text = LiveRecordingLocalStore.formatTimestamp(
            if (PublicVideoStore.isPublicPath(filename)) {
                PublicVideoStore.fileFor(filename).takeIf { it.exists() }?.lastModified()
            } else {
                RecordingFileResolver.resolve(requireContext(), filename)?.lastModified()
            } ?: System.currentTimeMillis(),
        )
        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(binding.videoPlayer)
        binding.videoPlayer.setMediaController(mediaController)
        binding.videoPlayer.setVideoURI(uri)
        binding.videoPlayer.setOnPreparedListener { player ->
            val (width, height) = VideoDisplaySize.resolve(
                context = requireContext(),
                uri = uri,
                fallbackWidth = player.videoWidth,
                fallbackHeight = player.videoHeight,
                preferPortraitDisplay = true,
            )
            binding.videoPlayer.setDisplaySizeAfterPrepare(width, height)
            player.isLooping = false
            binding.videoPlayer.start()
        }

        binding.btnDeleteRecording.setOnClickListener {
            LiveRecordingDeletePrompt.show(requireContext()) {
                if (PublicVideoStore.isPublicPath(filename)) {
                    PublicVideoStore.delete(requireContext(), filename)
                } else {
                    val file = RecordingFileResolver.resolve(requireContext(), filename)
                    if (file != null) {
                        val entry = LiveRecordingEntry(
                            id = file.nameWithoutExtension,
                            filename = filename,
                            createdAtMillis = file.lastModified(),
                            durationMillis = null,
                        )
                        LiveRecordingLocalStore.deleteRecording(requireContext(), entry)
                    }
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
