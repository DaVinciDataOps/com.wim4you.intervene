package com.wim4you.intervene.data

import androidx.room.Entity
import com.google.firebase.firestore.GeoPoint
import java.util.Date

@Entity(tableName = "distress_data")
data class DistressData (
    var id: String,
    var personId: String,
    var location: GeoPoint = GeoPoint(0.0, 0.0),
    var Time: Date = Date()
)