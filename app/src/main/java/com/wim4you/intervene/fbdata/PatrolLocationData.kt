package com.wim4you.intervene.fbdata

import android.os.Parcelable
import com.google.firebase.database.PropertyName
import kotlinx.parcelize.Parcelize

@Parcelize
data class PatrolLocationData (
    @PropertyName("id")
    var id: String?,
    @PropertyName("g")
    var geohash: String? = null,
    @PropertyName("l")
    var locationArray: List<Double>? = null,

    @PropertyName("vigilanteId")
    val vigilanteId: String? = null,
    @PropertyName("name")
    var name: String? = null,

    // var location: Map<String,Double>? = null,
    @PropertyName("time")
    var time: Long? = null,
    @PropertyName("active")
    var isActive: Boolean? = false,
    @PropertyName("fcmToken")
    var fcmToken: String? = null
): Parcelable{
    constructor() : this("", "", null, null, null, null, null, null)
}
