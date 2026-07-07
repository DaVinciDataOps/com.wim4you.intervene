package com.wim4you.intervene.dao

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wim4you.intervene.data.DestinationHistory
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.data.VigilanteMemberData

@Database(
    entities = [
        PersonData::class,
        VigilanteData::class,
        VigilanteMemberData::class,
        DestinationHistory::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDataBase : RoomDatabase() {
    abstract fun personDataDao(): PersonDataDao
    abstract fun vigilanteDataDao(): VigilanteDataDao
    abstract fun destinationHistoryDao(): DestinationHistoryDao
}
