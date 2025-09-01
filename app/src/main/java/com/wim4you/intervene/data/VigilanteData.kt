package com.wim4you.intervene.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date
import java.util.UUID

@Entity(tableName = "vigilante_data")
data class VigilanteData (
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    var name: String,
    var isGroup: Boolean,
    var groupSize: Int,
    var groupOwnerId: String,
    val isCertifiedVigilante: Boolean,
    val isActive: Boolean,
)