package com.wim4you.intervene.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.GeoPoint
import java.util.Date

@Entity(tableName = "distress_data")
data class DistressData (
    @PrimaryKey
    var id: String,
    var personId: String,
    var location: GeoPoint = GeoPoint(0.0, 0.0),
    var Time: Date = Date()
)