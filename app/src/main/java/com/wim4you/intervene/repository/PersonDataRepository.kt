package com.wim4you.intervene.repository

import com.wim4you.intervene.dao.PersonDataDao
import com.wim4you.intervene.data.PersonData

class PersonDataRepository (private val personDataDao: PersonDataDao) {

    suspend fun fetch(): PersonData?  {
       return personDataDao.get()
    }

    suspend fun insertPersonData(personData: PersonData) {
        personDataDao.insert(personData)
    }

    suspend fun upsertPersonData(personData: PersonData) {
        personDataDao.upsert(personData)
    }

    suspend fun getAllPersonData(id:String): PersonData? {
        return personDataDao.getById(id)
    }
}