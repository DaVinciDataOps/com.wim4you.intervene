package com.wim4you.intervene.repository

import com.wim4you.intervene.dao.PersonDataDao
import com.wim4you.intervene.dao.VigilanteDataDao
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData

class VigilanteDataRepository (private val dao: VigilanteDataDao) {

    suspend fun insert(data: VigilanteData) {
        dao.insert(data)
    }

    suspend fun upsert(data: VigilanteData) {
        dao.upsert(data)
    }

    suspend fun fetch(id:String): VigilanteData? {
        return dao.getById(id)
    }

    suspend fun fetch(): VigilanteData? {
        return dao.get()
    }
}