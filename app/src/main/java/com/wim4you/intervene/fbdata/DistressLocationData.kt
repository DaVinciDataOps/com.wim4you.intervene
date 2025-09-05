package com.wim4you.intervene.fbdata

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DistressLocationData (
    var id: String,
    var personId: String,
    var location: Map<String,Double> = mapOf("latitude" to 0.0, "longitude" to 0.0),
    var time: Long = System.currentTimeMillis(),
    var fcmToken: String? = null,
    var isActive: Boolean? = false
): Parcelable{
    constructor() : this("", "", mapOf("latitude" to 0.0, "longitude" to 0.0), System.currentTimeMillis(), null, isActive = null)
}