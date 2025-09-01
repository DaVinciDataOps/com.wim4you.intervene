package com.wim4you.intervene.dao

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    private var instance: AppDataBase? = null

    fun getDatabase(context: Context): AppDataBase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDataBase::class.java,
                "app_database"
            ).build().also { instance = it }
        }
    }
}