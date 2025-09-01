package com.wim4you.intervene.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData

@Dao
interface VigilanteDataDao {
    @Insert
    suspend fun insert(vigilante: VigilanteData)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vigilante: VigilanteData)
    @Query("SELECT * FROM vigilante_data WHERE id = :id")
    suspend fun getById(id: String): VigilanteData?

    @Query("SELECT * FROM vigilante_data ORDER BY id DESC LIMIT 1")
    suspend fun get(): VigilanteData?
}