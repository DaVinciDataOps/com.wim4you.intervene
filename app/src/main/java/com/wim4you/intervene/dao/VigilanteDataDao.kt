package com.wim4you.intervene.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData

@Dao
interface VigilanteDataDao {
    @Insert
    suspend fun insert(vigilante: VigilanteData)

    @Query("SELECT * FROM vigilante_data WHERE id = :id")
    suspend fun getById(id: String): VigilanteData?
}