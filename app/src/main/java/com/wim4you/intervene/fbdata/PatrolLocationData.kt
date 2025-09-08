package com.wim4you.intervene.fbdata

import android.os.Parcelable
import com.google.firebase.database.PropertyName
import kotlinx.parcelize.Parcelize

@Parcelize
data class PatrolLocationData (
    @PropertyName("id")
    var id: String?,
    @PropertyName("g")
    val geohash: String? = null,
    @PropertyName("l")
    val locationArray: List<Double>? = null,

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

//    val location: GeoFireLocation?
//        get() = locationArray?.let {
//            if (it.size >= 2) GeoFireLocation(it[0], it[1]) else null
//        }
//
//    @Parcelize
//    data class GeoFireLocation(
//        @PropertyName("0") val latitude: Double = 0.0,
//        @PropertyName("1") val longitude: Double = 0.0
//    ) : Parcelable

    constructor() : this("", "", null, null, null, null, null, null)


}
