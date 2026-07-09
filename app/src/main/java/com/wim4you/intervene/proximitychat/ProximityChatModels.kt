package com.wim4you.intervene.proximitychat

data class NearbyChatUser(
    val uid: String,
    val alias: String,
    val distanceMeters: Double?,
    val latitude: Double?,
    val longitude: Double?,
)

data class ChatRoomSummary(
    val roomId: String,
    val displayName: String,
    val isGroup: Boolean,
    val participantCount: Int,
    val lastMessageAt: Long,
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
