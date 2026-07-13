package com.wim4you.intervene.liverecording

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
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentLiveRecordingListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LiveRecordingListFragment : Fragment() {
    private val viewModel: LiveRecordingListViewModel by viewModels()
    private var _binding: FragmentLiveRecordingListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LiveRecordingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveRecordingListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LiveRecordingAdapter(
            onRecordingClick = { entry -> navigateToPlayer(entry.filename) },
            onRecordingLongClick = { entry -> confirmDelete(entry) },
            onRecordingDeleteClick = { entry -> confirmDelete(entry) },
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

    private fun navigateToPlayer(filename: String) {
        val bundle = Bundle().apply {
            putString(LiveRecordingConstants.ARG_RECORDING_FILENAME, filename)
        }
        findNavController().navigate(R.id.nav_live_recording_player, bundle)
    }

    private fun confirmDelete(entry: LiveRecordingEntry) {
        LiveRecordingDeletePrompt.show(requireContext()) {
            viewModel.deleteRecording(requireContext(), entry)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
