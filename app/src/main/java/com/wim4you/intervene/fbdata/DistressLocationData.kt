package com.wim4you.intervene.fbdata

import android.os.Parcelable
import com.google.firebase.database.PropertyName
import kotlinx.parcelize.Parcelize

@Parcelize
data class DistressLocationData (
    @PropertyName("id")
    var id: String?,
    @PropertyName("personId")
    var personId: String?,
    @PropertyName("alias")
    var alias: String?,
    @PropertyName("g")
    var geohash: String? = null,
    @PropertyName("l")
    var locationArray: List<Double>? = null,
    @PropertyName("startTime")
    var startTime: Long? = System.currentTimeMillis(),
    @PropertyName("time")
    var time: Long? = System.currentTimeMillis(),
    @PropertyName("fcmToken")
    var fcmToken: String? = null,
    @PropertyName("active")
    var isActive: Boolean? = false
): Parcelable{
    constructor() : this(null, null, null, null, null, null, null)

    // Helper properties for convenience
    val latitude: Double? get() = locationArray?.getOrNull(0)
    val longitude: Double? get() = locationArray?.getOrNull(1)
}