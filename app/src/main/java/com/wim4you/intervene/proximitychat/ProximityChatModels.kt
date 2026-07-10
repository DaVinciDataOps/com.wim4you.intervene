package com.wim4you.intervene.proximitychat

data class NearbyChatUser(
    val uid: String,
    val alias: String,
    val distanceMeters: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val hasUnreadIndicator: Boolean = false,
)

data class ChatRoomSummary(
    val roomId: String,
    val displayName: String,
    val isGroup: Boolean,
    val participantCount: Int,
    val lastMessageAt: Long,
    val status: String = ProximityChatConstants.ROOM_STATUS_ACTIVE,
    val isIncomingRing: Boolean = false,
    val initiatorAlias: String? = null,
    val hasUnreadForMe: Boolean = false,
    val hasUnreadByOthers: Boolean = false,
    val hasUnreadIndicator: Boolean = false,
)

data class ChatRoomStatus(
    val status: String,
    val initiatorUid: String?,
    val myAccepted: Boolean,
    val acceptedCount: Int,
    val isGroup: Boolean,
)

data class ChatMessageItem(
    val id: String,
    val senderId: String,
    val senderAlias: String,
    val text: String,
    val isSpeech: Boolean,
    val timestamp: Long,
    val isMine: Boolean,
)
