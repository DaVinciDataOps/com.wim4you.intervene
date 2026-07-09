package com.wim4you.intervene.fbdata

import android.os.Parcelable
import com.google.firebase.database.PropertyName
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatParticipantData(
    @PropertyName("alias")
    var alias: String? = null,
    @PropertyName("g")
    var g: String? = null,
    @PropertyName("l")
    var l: List<Double>? = null,
    @PropertyName("time")
    var time: Long? = null,
    @PropertyName("active")
    var isActive: Boolean? = false,
) : Parcelable {
    constructor() : this(null, null, null, null, false)
}
