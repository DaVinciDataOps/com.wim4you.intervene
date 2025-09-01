package com.wim4you.intervene.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.GeoPoint
import java.util.Date

@Entity(tableName = "location_data")
data class LocationData (
    @PrimaryKey
    var id: String,
    var vigilanteId: String,
    var location: GeoPoint = GeoPoint(0.0, 0.0),
    var Time: Date = Date(),
    var IsActive: Boolean = false
)
