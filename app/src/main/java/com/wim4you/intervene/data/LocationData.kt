package com.wim4you.intervene.data

import androidx.room.Entity
import com.google.firebase.firestore.GeoPoint
import java.util.Date

@Entity(tableName = "location_data")
data class LocationData (
    var id: String,
    var vigilanteId: String,
    var location: GeoPoint = GeoPoint(0.0, 0.0),
    var Time: Date = Date(),
    var IsActive: Boolean = false
)
