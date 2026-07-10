package com.wim4you.intervene.proximitychat

import com.wim4you.intervene.AppModeController

object ProximityChatConstants {
    const val PRESENCE_PATH = "chat_presence"
    const val ROOMS_PATH = "chat_rooms"
    const val USER_ROOMS_PATH = "chat_user_rooms"
    const val MESSAGES_PATH = "chat_messages"

    const val ROOM_TYPE_DIRECT = "direct"
    const val ROOM_TYPE_GROUP = "group"

    const val ROOM_STATUS_RINGING = "ringing"
    const val ROOM_STATUS_ACTIVE = "active"
    const val ROOM_STATUS_DECLINED = "declined"

    val PROXIMITY_RADIUS_KM: Double = AppModeController.GEO_QUERY_RADIUS_KM

    const val PRESENCE_STALE_MS = 5 * 60 * 1000L

    const val REMOVED_MESSAGE_TEXT = "..."

    /** Maximum messages loaded per conversation to keep UI responsive. */
    const val MESSAGE_LOAD_LIMIT = 200

    fun isNotifiableChatStatus(status: String): Boolean =
        status == ROOM_STATUS_ACTIVE

    fun isMessagingAllowed(status: String): Boolean =
        status != ROOM_STATUS_DECLINED
}
