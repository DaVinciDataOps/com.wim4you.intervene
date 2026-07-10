package com.wim4you.intervene.proximitychat

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
import com.wim4you.intervene.ui.home.PatrolAlertSoundPlayer
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

        roomAdapter = ChatRoomAdapter(
            onRoomClick = { room -> navigateToConversation(room.roomId) },
            onRoomLongClick = { room -> showRemoveChatDialog(room) },
            onRoomDeleteClick = { room -> showRemoveChatDialog(room) },
        )
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
                        if (state.newIncomingRingRoomIds.isNotEmpty()) {
                            PatrolAlertSoundPlayer.playChatNotification(requireContext())
                            state.newIncomingRingRoomIds.forEach { roomId ->
                                viewModel.clearIncomingRingNotification(roomId)
                            }
                        }
                        if (state.newNearbyUnreadSenderUids.isNotEmpty()) {
                            PatrolAlertSoundPlayer.playChatNotification(requireContext())
                            state.newNearbyUnreadSenderUids.forEach { senderUid ->
                                viewModel.clearNearbyUnreadNotification(senderUid)
                            }
                        }
                        state.errorMessage?.let { showError(it) }
                    }
                }
            }
        }

        requestLocationAndStart()
    }

    private fun requestLocationAndStart() {
        requestLocation { location -> viewModel.start(location) }
    }

    private fun requestLocationAndRefresh() {
        binding.swipeRefresh.isRefreshing = true
        requestLocation { location ->
            if (location == null) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(requireContext(), R.string.error_location_failed, Toast.LENGTH_SHORT).show()
            }
            viewModel.refreshLocation(location)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun requestLocation(onLocation: (android.location.Location?) -> Unit) {
        LocationUtils.setLocation(requireContext()) { latLng ->
            if (latLng == null) {
                onLocation(null)
                return@setLocation
            }
            onLocation(
                android.location.Location("proximity_chat").apply {
                    latitude = latLng.latitude
                    longitude = latLng.longitude
                },
            )
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

    private fun showRemoveChatDialog(room: ChatRoomSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_remove_title)
            .setMessage(getString(R.string.chat_remove_message, room.displayName))
            .setPositiveButton(R.string.chat_remove_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.removeChat(room.roomId)
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
            "auth_anonymous_disabled" -> R.string.chat_error_auth_anonymous_disabled
            "auth_not_configured" -> R.string.chat_error_auth_not_configured
            "auth_network" -> R.string.chat_error_auth_network
            "auth_rate_limited" -> R.string.chat_error_auth_rate_limited
            "auth_failed" -> R.string.chat_error_auth
            "profile_failed" -> R.string.chat_error_profile
            "presence_failed" -> R.string.chat_error_presence
            "room_failed" -> R.string.chat_error_room
            "remove_failed" -> R.string.chat_error_remove
            else -> R.string.chat_error_generic
        }
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_LONG).show()
        viewModel.clearError()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
