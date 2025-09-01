package com.wim4you.intervene

import android.app.Application
import androidx.room.Room
import com.wim4you.intervene.dao.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InterVeneApplication :  Application()  {
    companion object {
        lateinit var database: AppDataBase
    }

    override fun onCreate() {
        super.onCreate()

//        CoroutineScope(Dispatchers.IO).launch {
//            applicationContext.deleteDatabase("app_database")
//        }

        val database = Room.databaseBuilder(
            applicationContext,
            AppDataBase::class.java, "app_database"
        ).build()
    }
}