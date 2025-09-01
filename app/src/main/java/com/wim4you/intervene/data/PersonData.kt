package com.wim4you.intervene.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "person_data")
data class PersonData (
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    var name: String,
    var alias: String,
    var gender: String,
    var age: Int,
    var phoneNumber: String,
    var eMail: String,
    var safeWord: String,
)
