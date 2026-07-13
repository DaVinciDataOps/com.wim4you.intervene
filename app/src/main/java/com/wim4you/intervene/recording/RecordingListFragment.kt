package com.wim4you.intervene.recording

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentRecordingListBinding
import com.wim4you.intervene.distressstream.DistressStreamConstants
import com.wim4you.intervene.liverecording.LiveRecordingConstants
import com.wim4you.intervene.liverecording.LiveRecordingDeletePrompt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecordingListFragment : Fragment() {
    private val viewModel: RecordingListViewModel by viewModels()
    private var _binding: FragmentRecordingListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RecordingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRecordingListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RecordingAdapter(
            onItemClick = { item -> navigateToPlayer(item) },
            onItemDeleteClick = { item -> confirmDelete(item) },
        )

        binding.recyclerRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecordings.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh(requireContext())
            binding.swipeRefresh.isRefreshing = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recordings.collectLatest { recordings ->
                    adapter.submitList(recordings)
                    binding.emptyRecordings.isVisible = recordings.isEmpty()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(requireContext())
    }

    private fun navigateToPlayer(item: RecordingListItem) {
        when (item) {
            is RecordingListItem.SingleRecording -> {
                val bundle = Bundle().apply {
                    putString(LiveRecordingConstants.ARG_RECORDING_FILENAME, item.relativePath)
                }
                findNavController().navigate(R.id.nav_live_recording_player, bundle)
            }
            is RecordingListItem.DistressSession -> {
                val bundle = Bundle().apply {
                    putString(DistressStreamConstants.ARG_SESSION_ID, item.sessionPath)
                }
                findNavController().navigate(R.id.nav_distress_recording_player, bundle)
            }
        }
    }

    private fun confirmDelete(item: RecordingListItem) {
        when (item) {
            is RecordingListItem.SingleRecording -> {
                LiveRecordingDeletePrompt.show(requireContext()) {
                    viewModel.deleteItem(requireContext(), item)
                }
            }
            is RecordingListItem.DistressSession -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.distress_recording_delete_title)
                    .setMessage(R.string.distress_recording_delete_message)
                    .setPositiveButton(R.string.live_recording_delete_confirm) { _, _ ->
                        viewModel.deleteItem(requireContext(), item)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
