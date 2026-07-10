package com.wim4you.intervene.proximitychat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.AppPreferences
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.ProximityChatRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProximityChatConversationUiState(
    val roomId: String = "",
    val roomTitle: String = "",
    val myUid: String? = null,
    val myAlias: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val draftText: String = "",
    val isSending: Boolean = false,
    val isListening: Boolean = false,
    val speechToTextEnabled: Boolean = true,
    val errorMessage: String? = null,
    val roomStatus: String = ProximityChatConstants.ROOM_STATUS_ACTIVE,
    val isInitiator: Boolean = false,
    val isIncomingRing: Boolean = false,
    val canSendMessages: Boolean = true,
    val acceptedCount: Int = 0,
)

@HiltViewModel
class ProximityChatConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ProximityChatRepository,
    private val personDataRepository: PersonDataRepository,
    private val vigilanteDataRepository: VigilanteDataRepository,
) : ViewModel() {

    private val roomId: String = savedStateHandle.get<String>(ARG_ROOM_ID).orEmpty()

    private val _uiState = MutableStateFlow(ProximityChatConversationUiState(roomId = roomId))
    val uiState: StateFlow<ProximityChatConversationUiState> = _uiState.asStateFlow()

    private var lastSpokenMessageId: String? = null

    init {
        if (roomId.isNotEmpty()) {
            start()
        }
    }

    private fun start() {
        viewModelScope.launch {
            try {
                val uid = chatRepository.ensureAuthenticated()
                val alias = resolveAlias()
                val title = chatRepository.getRoomDisplayName(roomId, uid)
                _uiState.update {
                    it.copy(myUid = uid, myAlias = alias, roomTitle = title, errorMessage = null)
                }
                launch {
                    chatRepository.observeRoomStatus(roomId, uid).collect { status ->
                        val isInitiator = status.initiatorUid == uid
                        val isIncomingRing = status.status == ProximityChatConstants.ROOM_STATUS_RINGING &&
                            !isInitiator && !status.myAccepted
                        val canSend = status.status == ProximityChatConstants.ROOM_STATUS_ACTIVE
                        _uiState.update {
                            it.copy(
                                roomStatus = status.status,
                                isInitiator = isInitiator,
                                isIncomingRing = isIncomingRing,
                                canSendMessages = canSend,
                                acceptedCount = status.acceptedCount,
                            )
                        }
                        if (canSend) {
                            markConversationRead(_uiState.value.messages, uid)
                        }
                    }
                }
                chatRepository.observeMessages(roomId, uid).collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                    markConversationRead(messages, uid)
                    maybeReadLatestIncoming(messages, uid)
                }
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to start conversation", exception)
                _uiState.update { it.copy(errorMessage = "chat_load_failed") }
            }
        }
    }

    fun updateDraft(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    fun sendTextMessage() {
        val state = _uiState.value
        val uid = state.myUid ?: return
        val text = state.draftText.trim()
        if (text.isEmpty() || state.isSending || !state.canSendMessages) return
        sendMessage(text, isSpeech = false)
    }

    fun sendSpeechMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !_uiState.value.canSendMessages) return
        _uiState.update { it.copy(draftText = trimmed) }
        sendMessage(trimmed, isSpeech = true)
    }

    fun setListening(isListening: Boolean) {
        _uiState.update { it.copy(isListening = isListening) }
    }

    fun onMessageSpoken(messageId: String) {
        lastSpokenMessageId = messageId
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun acceptInvite() {
        val uid = _uiState.value.myUid ?: return
        viewModelScope.launch {
            try {
                chatRepository.acceptChatInvite(roomId, uid)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to accept chat invite", exception)
                _uiState.update { it.copy(errorMessage = "accept_failed") }
            }
        }
    }

    fun declineInvite() {
        val uid = _uiState.value.myUid ?: return
        viewModelScope.launch {
            try {
                chatRepository.declineChatInvite(roomId, uid)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to decline chat invite", exception)
                _uiState.update { it.copy(errorMessage = "decline_failed") }
            }
        }
    }

    private fun sendMessage(text: String, isSpeech: Boolean) {
        val state = _uiState.value
        val uid = state.myUid ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            try {
                chatRepository.sendMessage(
                    roomId = roomId,
                    senderId = uid,
                    senderAlias = state.myAlias,
                    text = text,
                    isSpeech = isSpeech,
                )
                _uiState.update { it.copy(draftText = "", isSending = false) }
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to send message", exception)
                _uiState.update { it.copy(isSending = false, errorMessage = "send_failed") }
            }
        }
    }

    private fun markConversationRead(messages: List<ChatMessageItem>, myUid: String) {
        if (_uiState.value.roomStatus != ProximityChatConstants.ROOM_STATUS_ACTIVE) return
        val readAt = messages.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        viewModelScope.launch {
            try {
                chatRepository.updateLastReadAt(roomId, myUid, readAt)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to update last read timestamp", exception)
            }
        }
    }

    private fun maybeReadLatestIncoming(messages: List<ChatMessageItem>, myUid: String) {
        if (!AppPreferences.isReadAloudEnabled(context)) return
        val latestIncoming = messages.lastOrNull { !it.isMine && it.senderId != myUid } ?: return
        if (latestIncoming.id == lastSpokenMessageId) return
        lastSpokenMessageId = latestIncoming.id
        _pendingSpeechText.value = latestIncoming.text
    }

    private val _pendingSpeechText = MutableStateFlow<String?>(null)
    val pendingSpeechText: StateFlow<String?> = _pendingSpeechText.asStateFlow()

    fun consumePendingSpeech() {
        _pendingSpeechText.value = null
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

    companion object {
        const val ARG_ROOM_ID = "roomId"
        private const val TAG = "ProximityChatConversationVM"
    }
}
