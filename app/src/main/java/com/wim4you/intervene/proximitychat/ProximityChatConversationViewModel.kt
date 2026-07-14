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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val canSendMessages: Boolean = true,
    val roomClosed: Boolean = false,
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
    private var readReceiptJob: Job? = null
    private var pendingReadAt: Long = 0L
    private var lastPersistedReadAt: Long = 0L

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
                chatRepository.ensureChatActive(roomId, uid, alias)
                launch {
                    chatRepository.observeRoomStatus(roomId, uid).collect { status ->
                        if (!status.exists) {
                            _uiState.update {
                                it.copy(roomClosed = true, canSendMessages = false)
                            }
                            return@collect
                        }
                        val canSend = ProximityChatConstants.isMessagingAllowed(status.status)
                        _uiState.update {
                            it.copy(
                                roomStatus = status.status,
                                canSendMessages = canSend,
                                roomClosed = false,
                            )
                        }
                        if (status.status != ProximityChatConstants.ROOM_STATUS_DECLINED) {
                            markConversationAccessed(_uiState.value.messages, uid)
                        }
                    }
                }
                chatRepository.observeMessages(roomId, uid).collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                    markConversationAccessed(messages, uid)
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

    suspend fun removeChat(): Boolean {
        val uid = _uiState.value.myUid ?: return false
        return try {
            chatRepository.removeChatRoom(roomId, uid)
            true
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Failed to remove chat", exception)
            _uiState.update { it.copy(errorMessage = "remove_failed") }
            false
        }
    }

    fun removeMessage(messageId: String) {
        val uid = _uiState.value.myUid ?: return
        val previousMessage = _uiState.value.messages.find { it.id == messageId } ?: return
        if (!previousMessage.isMine || previousMessage.isDeleted) return
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(isDeleted = true, text = "")
                    } else {
                        message
                    }
                },
            )
        }
        viewModelScope.launch {
            try {
                chatRepository.deleteMessage(roomId, messageId, uid)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to remove message", exception)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { message ->
                            if (message.id == messageId) previousMessage else message
                        },
                        errorMessage = "remove_message_failed",
                    )
                }
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

    private fun markConversationAccessed(messages: List<ChatMessageItem>, myUid: String) {
        if (_uiState.value.roomStatus == ProximityChatConstants.ROOM_STATUS_DECLINED) return
        val readAt = messages.maxOfOrNull { it.timestamp } ?: return
        if (readAt <= lastPersistedReadAt) return
        pendingReadAt = maxOf(pendingReadAt, readAt)
        readReceiptJob?.cancel()
        readReceiptJob = viewModelScope.launch {
            delay(READ_RECEIPT_DEBOUNCE_MS)
            val toWrite = pendingReadAt
            if (toWrite <= lastPersistedReadAt) return@launch
            try {
                chatRepository.updateLastReadAt(roomId, myUid, toWrite)
                lastPersistedReadAt = toWrite
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to update last read timestamp", exception)
            }
        }
    }

    private fun maybeReadLatestIncoming(messages: List<ChatMessageItem>, myUid: String) {
        if (!AppPreferences.isReadAloudEnabled(context)) return
        val latestIncoming = messages.lastOrNull {
            !it.isMine && it.senderId != myUid && !it.isDeleted
        } ?: return
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
        personDataRepository.fetch()?.alias?.takeIf { it.isNotBlank() }?.let { return it }
        vigilanteDataRepository.fetch()?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return "User"
    }

    companion object {
        const val ARG_ROOM_ID = "roomId"
        private const val TAG = "ProximityChatConversationVM"
        private const val READ_RECEIPT_DEBOUNCE_MS = 1_500L
    }
}
