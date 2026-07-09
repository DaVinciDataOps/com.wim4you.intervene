package com.wim4you.intervene.proximitychat

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.AppModeController
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
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    fun start(location: Location?) {
        if (location == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "location_unavailable") }
            return
        }
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        viewModelScope.launch {
            try {
                val uid = chatRepository.ensureAuthenticated()
                val alias = resolveAlias()
                _uiState.update { it.copy(myUid = uid, myAlias = alias, isLoading = false, errorMessage = null) }
                startPresenceUpdates(uid, alias)
                observeNearby(uid, location.latitude, location.longitude)
                observeRooms(uid)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to start proximity chat", exception)
                _uiState.update { it.copy(isLoading = false, errorMessage = "auth_failed") }
            }
        }
    }

    fun refreshLocation(location: Location?) {
        if (location == null) return
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        val state = _uiState.value
        val uid = state.myUid ?: return
        nearbyJob?.cancel()
        observeNearby(uid, location.latitude, location.longitude)
        viewModelScope.launch {
            try {
                chatRepository.updatePresence(uid, state.myAlias, location.latitude, location.longitude)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to refresh chat presence", exception)
            }
        }
    }

    fun toggleUserSelection(uid: String) {
        _uiState.update { state ->
            val updated = state.selectedUserIds.toMutableSet()
            if (!updated.add(uid)) {
                updated.remove(uid)
            }
            state.copy(selectedUserIds = updated)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedUserIds = emptySet()) }
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        presenceJob?.cancel()
        nearbyJob?.cancel()
        roomsJob?.cancel()
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
                _uiState.update { it.copy(nearbyUsers = users) }
            }
        }
    }

    private fun observeRooms(uid: String) {
        roomsJob?.cancel()
        roomsJob = viewModelScope.launch {
            chatRepository.observeMyRooms(uid).collect { rooms ->
                _uiState.update { it.copy(rooms = rooms) }
            }
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
