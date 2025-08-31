package com.wim4you.intervene.data

import androidx.room.Entity
import java.util.UUID

@Entity(tableName = "person_data")
data class PersonData (
    var id: String = UUID.randomUUID().toString(),
    var name: String,
    var alias: String,
    var gender: String,
    var age: Int,
    var safeWord: String,
    var phoneNumber: String
)
