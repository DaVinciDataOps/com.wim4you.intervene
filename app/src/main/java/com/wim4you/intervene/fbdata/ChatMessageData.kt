package com.wim4you.intervene.fbdata

import com.google.firebase.database.PropertyName

data class ChatMessageData(
    @PropertyName("senderId")
    var senderId: String? = null,
    @PropertyName("senderAlias")
    var senderAlias: String? = null,
    @PropertyName("text")
    var text: String? = null,
    @PropertyName("isSpeech")
    var isSpeech: Boolean? = false,
    @PropertyName("timestamp")
    var timestamp: Long? = null,
    @get:PropertyName("deleted")
    @set:PropertyName("deleted")
    var deleted: Boolean? = false,
) {
    constructor() : this(null, null, null, false, null, false)
}
