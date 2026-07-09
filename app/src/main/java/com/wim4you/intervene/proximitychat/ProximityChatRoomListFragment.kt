package com.wim4you.intervene.proximitychat

import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentProximityChatRoomsBinding
import com.wim4you.intervene.location.LocationUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProximityChatRoomListFragment : Fragment() {

    private val viewModel: ProximityChatRoomListViewModel by viewModels()
    private var _binding: FragmentProximityChatRoomsBinding? = null
    private val binding get() = _binding!!

    private lateinit var roomAdapter: ChatRoomAdapter
    private lateinit var nearbyAdapter: NearbyChatUserAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProximityChatRoomsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        roomAdapter = ChatRoomAdapter { room ->
            navigateToConversation(room.roomId)
        }
        nearbyAdapter = NearbyChatUserAdapter(
            selectedIds = { viewModel.uiState.value.selectedUserIds },
            onUserClick = { user ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val roomId = viewModel.openDirectChat(user)
                    if (roomId != null) {
                        navigateToConversation(roomId)
                    }
                }
            },
            onUserLongClick = { user ->
                viewModel.toggleUserSelection(user.uid)
                nearbyAdapter.notifyDataSetChanged()
                updateSelectionUi()
            },
        )

        binding.recyclerChatRooms.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerChatRooms.adapter = roomAdapter
        binding.recyclerNearbyUsers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerNearbyUsers.adapter = nearbyAdapter

        binding.btnCreateGroup.setOnClickListener { showGroupNameDialog() }
        binding.btnClearSelection.setOnClickListener {
            viewModel.clearSelection()
            nearbyAdapter.notifyDataSetChanged()
            updateSelectionUi()
        }
        binding.swipeRefresh.setOnRefreshListener { requestLocationAndRefresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        roomAdapter.submitList(state.rooms)
                        nearbyAdapter.submitList(state.nearbyUsers)
                        binding.emptyChatRooms.isVisible = state.rooms.isEmpty()
                        binding.emptyNearbyUsers.isVisible = state.nearbyUsers.isEmpty() && !state.isLoading
                        binding.progressLoading.isVisible = state.isLoading
                        binding.swipeRefresh.isRefreshing = false
                        binding.tvProximityRadius.text = getString(
                            R.string.chat_proximity_radius,
                            ProximityChatConstants.PROXIMITY_RADIUS_KM,
                        )
                        updateSelectionUi()
                        state.errorMessage?.let { showError(it) }
                    }
                }
            }
        }

        requestLocationAndStart()
    }

    private fun requestLocationAndStart() {
        LocationUtils.getLocation(requireContext()) { latLng ->
            if (latLng == null) {
                viewModel.start(null)
                return@getLocation
            }
            val location = Location("proximity_chat").apply {
                latitude = latLng.latitude
                longitude = latLng.longitude
            }
            viewModel.start(location)
        }
    }

    private fun requestLocationAndRefresh() {
        LocationUtils.getLocation(requireContext()) { latLng ->
            if (latLng == null) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(requireContext(), R.string.error_location_failed, Toast.LENGTH_SHORT).show()
                return@getLocation
            }
            val location = Location("proximity_chat").apply {
                latitude = latLng.latitude
                longitude = latLng.longitude
            }
            viewModel.refreshLocation(location)
        }
    }

    private fun updateSelectionUi() {
        val selectedCount = viewModel.uiState.value.selectedUserIds.size
        binding.selectionBar.isVisible = selectedCount > 0
        binding.btnCreateGroup.isEnabled = selectedCount >= 2 && !viewModel.uiState.value.isCreatingGroup
        binding.tvSelectionCount.text = getString(R.string.chat_selected_count, selectedCount)
    }

    private fun showGroupNameDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.chat_group_name_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_create_group_title)
            .setView(input)
            .setPositiveButton(R.string.chat_create_group_confirm) { _, _ ->
                val name = input.text.toString().trim().ifBlank {
                    getString(R.string.chat_default_group_name)
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val roomId = viewModel.createGroupChat(name)
                    if (roomId != null) {
                        navigateToConversation(roomId)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun navigateToConversation(roomId: String) {
        val bundle = Bundle().apply {
            putString(ProximityChatConversationViewModel.ARG_ROOM_ID, roomId)
        }
        findNavController().navigate(R.id.nav_proximity_chat_conversation, bundle)
    }

    private fun showError(key: String) {
        val messageRes = when (key) {
            "location_unavailable" -> R.string.chat_error_location
            "auth_failed" -> R.string.chat_error_auth
            "room_failed" -> R.string.chat_error_room
            else -> R.string.chat_error_generic
        }
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        viewModel.clearError()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
