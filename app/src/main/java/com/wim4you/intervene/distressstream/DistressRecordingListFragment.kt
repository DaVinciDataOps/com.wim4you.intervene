package com.wim4you.intervene.distressstream

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
import com.wim4you.intervene.databinding.FragmentDistressRecordingListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DistressRecordingListFragment : Fragment() {
    private val viewModel: DistressRecordingListViewModel by viewModels()
    private var _binding: FragmentDistressRecordingListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DistressRecordingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDistressRecordingListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DistressRecordingAdapter(
            onSessionClick = { session -> navigateToPlayer(session.sessionId) },
            onSessionDeleteClick = { session -> confirmDelete(session) },
        )
        binding.recyclerRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecordings.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh(requireContext())
            binding.swipeRefresh.isRefreshing = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessions.collectLatest { sessions ->
                    adapter.submitList(sessions)
                    binding.emptyRecordings.isVisible = sessions.isEmpty()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(requireContext())
    }

    private fun navigateToPlayer(sessionId: String) {
        val bundle = Bundle().apply {
            putString(DistressStreamConstants.ARG_SESSION_ID, sessionId)
        }
        findNavController().navigate(R.id.nav_distress_recording_player, bundle)
    }

    private fun confirmDelete(session: DistressRecordingSession) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.distress_recording_delete_title)
            .setMessage(R.string.distress_recording_delete_message)
            .setPositiveButton(R.string.live_recording_delete_confirm) { _, _ ->
                viewModel.deleteSession(requireContext(), session)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
