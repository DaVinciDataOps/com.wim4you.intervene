package com.wim4you.intervene.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.wim4you.intervene.data.DestinationHistory

@Dao
interface DestinationHistoryDao {
    @Insert
    suspend fun insert(entry: DestinationHistory)

    @Query("SELECT * FROM destination_history ORDER BY usedAt DESC LIMIT 150")
    suspend fun getRecent(): List<DestinationHistory>

    @Query(
        """
        DELETE FROM destination_history
        WHERE id NOT IN (
            SELECT id FROM destination_history ORDER BY usedAt DESC LIMIT 150
        )
        """
    )
    suspend fun trimToMaxEntries()
}
