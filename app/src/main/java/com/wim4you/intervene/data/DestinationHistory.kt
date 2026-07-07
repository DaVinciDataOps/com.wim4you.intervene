package com.wim4you.intervene.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "destination_history",
    indices = [Index(value = ["usedAt"])],
)
data class DestinationHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val address: String,
    val usedAt: Long,
)
