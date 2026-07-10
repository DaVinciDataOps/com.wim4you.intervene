package com.wim4you.intervene.fbdata

import com.google.firebase.database.PropertyName

data class ChatRoomData(
    @PropertyName("type")
    var type: String? = null,
    @PropertyName("name")
    var name: String? = null,
    @PropertyName("createdAt")
    var createdAt: Long? = null,
    @PropertyName("lastMessageAt")
    var lastMessageAt: Long? = null,
    @PropertyName("status")
    var status: String? = null,
    @PropertyName("initiatorUid")
    var initiatorUid: String? = null,
) {
    constructor() : this(null, null, null, null, null, null)
}
