package com.wim4you.intervene.data

import androidx.room.Entity
import com.google.firebase.firestore.GeoPoint
import java.util.Date
import java.util.UUID

@Entity(tableName = "vigilante_data")
data class VigilanteData (

    var id: String = UUID.randomUUID().toString(),
    var name: String,
    var isGroup: Boolean,
    var groupSize: Int,
    var location: GeoPoint = GeoPoint(0.0, 0.0),
    val isCertifiedVigilante: Boolean = false,
    val isActive: Boolean = false,
    val lastActive: Date = Date()
){
    constructor() : this(
        "",
        "",
        false,
        1,
        GeoPoint(0.0, 0.0),
        false,
        false,
        Date()
        )
}