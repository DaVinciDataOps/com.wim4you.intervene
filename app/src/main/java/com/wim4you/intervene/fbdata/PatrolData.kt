package com.wim4you.intervene.fbdata

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PatrolData (
    var id: String,
    var vigilanteId: String,
    var name: String? = null,
    var location: Map<String,Double>? = null,
    var Time: Long? = null,
    var isActive: Boolean? = false,
    var fcmToken: String? = null
): Parcelable{
    constructor() : this("", "", null, null, null, null, null)
}
