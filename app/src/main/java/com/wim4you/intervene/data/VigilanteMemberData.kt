package com.wim4you.intervene.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "vigilante_member_data")
data class VigilanteMemberData (
    @PrimaryKey
    var id: Number,
    var personId: String
)

