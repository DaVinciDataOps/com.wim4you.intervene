package com.wim4you.intervene.dao

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wim4you.intervene.data.*

@Database(entities = [PersonData::class, VigilanteData::class],
    version = 1, exportSchema = false)
abstract class AppDataBase : RoomDatabase() {
    abstract fun personDataDao(): PersonDataDao
    abstract fun vigilanteDataDao(): VigilanteDataDao
}