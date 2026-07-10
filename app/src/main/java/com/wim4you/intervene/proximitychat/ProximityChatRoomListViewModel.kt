package com.wim4you.intervene.proximitychat

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.ProximityChatRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProximityChatRoomListUiState(
    val myUid: String? = null,
    val myAlias: String = "",
    val nearbyUsers: List<NearbyChatUser> = emptyList(),
    val rooms: List<ChatRoomSummary> = emptyList(),
    val selectedUserIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isCreatingGroup: Boolean = false,
    val errorMessage: String? = null,
    val newIncomingRingRoomIds: Set<String> = emptySet(),
    val unreadSenderUids: Set<String> = emptySet(),
    val newNearbyUnreadSenderUids: Set<String> = emptySet(),
)

@HiltViewModel
class ProximityChatRoomListViewModel @Inject constructor(
    private val chatRepository: ProximityChatRepository,
    private val personDataRepository: PersonDataRepository,
    private val vigilanteDataRepository: VigilanteDataRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProximityChatRoomListUiState())
    val uiState: StateFlow<ProximityChatRoomListUiState> = _uiState.asStateFlow()

    private var presenceJob: Job? = null
    private var nearbyJob: Job? = null
    private var roomsJob: Job? = null
    private var unreadSendersJob: Job? = null
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var knownRoomIds = mutableSetOf<String>()
    private var knownUnreadSenders = mutableSetOf<String>()
    private var notifiedUnreadSenders = mutableSetOf<String>()

    fun start(location: Location?) {
        lastLatitude = location?.latitude
        lastLongitude = location?.longitude
        viewModelScope.launch {
            val uid = try {
                chatRepository.ensureAuthenticated()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to authenticate for proximity chat", exception)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = FirebaseAuthManager.authFailureKey(exception),
                    )
                }
                return@launch
            }

            val alias = try {
                resolveAlias()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to resolve chat alias", exception)
                _uiState.update { it.copy(isLoading = false, errorMessage = "profile_failed") }
                return@launch
            }

            _uiState.update {
                it.copy(myUid = uid, myAlias = alias, isLoading = false, errorMessage = null)
            }
            observeRooms(uid)
            observeIncomingUnreadSenders(uid)

            if (location == null) {
                _uiState.update { it.copy(errorMessage = "location_unavailable") }
                return@launch
            }

            startPresenceUpdates(uid, alias)
            observeNearby(uid, location.latitude, location.longitude)
        }
    }

    fun refreshLocation(location: Location?) {
        if (location == null) {
            _uiState.update { it.copy(errorMessage = "location_unavailable") }
            return
        }
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        val state = _uiState.value
        val uid = state.myUid
        if (uid == null) {
            start(location)
            return
        }
        _uiState.update { it.copy(errorMessage = null) }
        nearbyJob?.cancel()
        observeNearby(uid, location.latitude, location.longitude)
        if (presenceJob?.isActive != true) {
            startPresenceUpdates(uid, state.myAlias)
        }
        viewModelScope.launch {
            try {
                chatRepository.updatePresence(uid, state.myAlias, location.latitude, location.longitude)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to refresh chat presence", exception)
                _uiState.update { it.copy(errorMessage = "presence_failed") }
            }
        }
    }

    fun toggleUserSelection(uid: String) {
        _uiState.update { state ->
            val updated = state.selectedUserIds.toMutableSet()
            if (!updated.add(uid)) {
                updated.remove(uid)
            }
            val myUid = state.myUid
            state.copy(
                selectedUserIds = updated,
                nearbyUsers = if (myUid == null) {
                    state.nearbyUsers.map { it.copy(isSelected = it.uid in updated) }
                } else {
                    applyNearbyUserState(
                        state.nearbyUsers,
                        state.unreadSenderUids,
                        updated,
                        state.rooms,
                        myUid,
                    )
                },
            )
        }
    }

    fun clearSelection() {
        _uiState.update { state ->
            state.copy(
                selectedUserIds = emptySet(),
                nearbyUsers = state.nearbyUsers.map { it.copy(isSelected = false) },
            )
        }
    }

    suspend fun openDirectChat(otherUser: NearbyChatUser): String? {
        val state = _uiState.value
        val myUid = state.myUid ?: return null
        return try {
            chatRepository.ensureDirectRoom(
                myUid = myUid,
                otherUid = otherUser.uid,
                myAlias = state.myAlias,
                otherAlias = otherUser.alias,
            )
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to open direct chat", exception)
            _uiState.update { it.copy(errorMessage = "room_failed") }
            null
        }
    }

    suspend fun removeChat(roomId: String) {
        val myUid = _uiState.value.myUid ?: return
        try {
            chatRepository.removeChatRoom(roomId, myUid)
            knownRoomIds.remove(roomId)
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to remove chat", exception)
            _uiState.update { it.copy(errorMessage = "remove_failed") }
        }
    }

    suspend fun clearAllChats() {
        val myUid = _uiState.value.myUid ?: return
        try {
            chatRepository.clearAllChatRooms(myUid)
            knownRoomIds.clear()
            notifiedUnreadSenders.clear()
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to clear all chats", exception)
            _uiState.update { it.copy(errorMessage = "clear_all_failed") }
        }
    }

    suspend fun createGroupChat(groupName: String): String? {
        val state = _uiState.value
        val myUid = state.myUid ?: return null
        val selected = state.selectedUserIds
        if (selected.size < 2) return null
        _uiState.update { it.copy(isCreatingGroup = true) }
        return try {
            val aliases = state.nearbyUsers
                .filter { it.uid in selected }
                .associate { it.uid to it.alias } + (myUid to state.myAlias)
            val roomId = chatRepository.createGroupRoom(
                myUid = myUid,
                participantUids = selected,
                aliases = aliases,
                groupName = groupName,
            )
            _uiState.update { it.copy(selectedUserIds = emptySet(), isCreatingGroup = false) }
            roomId
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to create group chat", exception)
            _uiState.update { it.copy(isCreatingGroup = false, errorMessage = "room_failed") }
            null
        }
    }

    fun clearIncomingRingNotification(roomId: String) {
        knownRoomIds.remove(roomId)
        _uiState.update { it.copy(newIncomingRingRoomIds = emptySet()) }
    }

    fun clearNearbyUnreadNotification(senderUid: String) {
        notifiedUnreadSenders.add(senderUid)
        _uiState.update { it.copy(newNearbyUnreadSenderUids = emptySet()) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        presenceJob?.cancel()
        nearbyJob?.cancel()
        roomsJob?.cancel()
        unreadSendersJob?.cancel()
        val uid = _uiState.value.myUid
        if (uid != null) {
            viewModelScope.launch {
                try {
                    chatRepository.clearPresence(uid)
                } catch (exception: Exception) {
                    SecureLog.e(TAG, "Failed to clear chat presence", exception)
                }
            }
        }
        super.onCleared()
    }

    private fun startPresenceUpdates(uid: String, alias: String) {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            while (isActive) {
                val lat = lastLatitude
                val lng = lastLongitude
                if (lat != null && lng != null) {
                    try {
                        chatRepository.updatePresence(uid, alias, lat, lng)
                    } catch (exception: Exception) {
                        SecureLog.e(TAG, "Failed to update chat presence", exception)
                    }
                }
                delay(AppModeController.LOCATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun observeNearby(uid: String, latitude: Double, longitude: Double) {
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            chatRepository.observeNearbyUsers(latitude, longitude, uid).collect { users ->
                _uiState.update { state ->
                    state.copy(
                        nearbyUsers = applyNearbyUserState(
                            users,
                            state.unreadSenderUids,
                            state.selectedUserIds,
                            state.rooms,
                            uid,
                        ),
                    )
                }
            }
        }
    }

    private fun observeIncomingUnreadSenders(uid: String) {
        unreadSendersJob?.cancel()
        unreadSendersJob = viewModelScope.launch {
            chatRepository.observeIncomingUnreadSenderUids(uid).collect { unreadSenderUids ->
                val clearedSenders = knownUnreadSenders - unreadSenderUids
                notifiedUnreadSenders.removeAll(clearedSenders)
                knownUnreadSenders = unreadSenderUids.toMutableSet()
                val newUnreadSenders = unreadSenderUids - notifiedUnreadSenders
                _uiState.update { state ->
                    state.copy(
                        unreadSenderUids = unreadSenderUids,
                        nearbyUsers = applyNearbyUserState(
                            state.nearbyUsers,
                            unreadSenderUids,
                            state.selectedUserIds,
                            state.rooms,
                            uid,
                        ),
                        newNearbyUnreadSenderUids = newUnreadSenders,
                    )
                }
            }
        }
    }

    private fun observeRooms(uid: String) {
        roomsJob?.cancel()
        roomsJob = viewModelScope.launch {
            chatRepository.observeMyRooms(uid).collect { rooms ->
                val roomIds = rooms.map { it.roomId }.toSet()
                val newRoomIds = roomIds - knownRoomIds
                knownRoomIds.addAll(roomIds)
                knownRoomIds.retainAll(roomIds)
                _uiState.update { state ->
                    state.copy(
                        rooms = rooms,
                        nearbyUsers = applyNearbyUserState(
                            state.nearbyUsers,
                            state.unreadSenderUids,
                            state.selectedUserIds,
                            rooms,
                            uid,
                        ),
                        newIncomingRingRoomIds = newRoomIds,
                    )
                }
            }
        }
    }

    private fun applyNearbyUserState(
        nearbyUsers: List<NearbyChatUser>,
        unreadSenderUids: Set<String>,
        selectedUserIds: Set<String>,
        rooms: List<ChatRoomSummary>,
        myUid: String,
    ): List<NearbyChatUser> {
        val directRoomsByOtherUid = rooms
            .filter { !it.isGroup }
            .mapNotNull { room ->
                chatRepository.otherUidFromDirectRoom(room.roomId, myUid)?.let { it to room }
            }
            .toMap()
        return nearbyUsers.map { user ->
            val room = directRoomsByOtherUid[user.uid]
            user.copy(
                hasUnreadIndicator = user.uid in unreadSenderUids || room?.hasUnreadForMe == true,
                isSelected = user.uid in selectedUserIds,
            )
        }
    }

    private suspend fun resolveAlias(): String {
        val person = personDataRepository.fetch()
        if (!person?.alias.isNullOrBlank()) {
            return person!!.alias
        }
        val vigilante = vigilanteDataRepository.fetch()
        if (!vigilante?.name.isNullOrBlank()) {
            return vigilante!!.name
        }
        return "User"
    }

    private companion object {
        const val TAG = "ProximityChatRoomListVM"
    }
}
