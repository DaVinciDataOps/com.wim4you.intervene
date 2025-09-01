package com.wim4you.intervene.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wim4you.intervene.data.VigilanteMemberData

@Dao
interface VigilanteMemberDataDao {
    @Insert
    suspend fun insert(vigilanteMember: VigilanteMemberData)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vigilanteMember: VigilanteMemberData)

    @Query("SELECT * FROM vigilante_data WHERE id = :id")
    suspend fun getById(id: String): VigilanteMemberData?
}