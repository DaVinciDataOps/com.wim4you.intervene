package com.wim4you.intervene.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wim4you.intervene.data.PersonData

@Dao
interface PersonDataDao {
    @Insert
    suspend fun insert(person: PersonData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(personData: PersonData)

    @Query("SELECT * FROM person_data WHERE id = :id")
    suspend fun getById(id: String): PersonData?

    @Query("SELECT * FROM person_data ORDER BY id DESC LIMIT 1")
    suspend fun get(): PersonData?
}