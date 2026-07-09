package com.wim4you.intervene.repository

import com.wim4you.intervene.dao.PersonDataDao
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.security.SensitiveFieldProtector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonDataRepository @Inject constructor(
    private val personDataDao: PersonDataDao,
) {

    suspend fun fetch(): PersonData? {
        return personDataDao.get()?.let(::decryptPerson)
    }

    suspend fun insertPersonData(personData: PersonData) {
        personDataDao.insert(encryptPerson(personData))
    }

    suspend fun upsertPersonData(personData: PersonData) {
        personDataDao.upsert(encryptPerson(personData))
    }

    suspend fun getAllPersonData(id: String): PersonData? {
        return personDataDao.getById(id)?.let(::decryptPerson)
    }

    /**
     * Re-encrypts legacy plaintext PII after an app update that adds field encryption.
     */
    suspend fun migratePlaintextFieldsIfNeeded() {
        val stored = personDataDao.get() ?: return
        if (isPersonEncrypted(stored)) return
        personDataDao.upsert(encryptPerson(decryptPerson(stored)))
    }

    private fun encryptPerson(person: PersonData): PersonData = person.copy(
        phoneNumber = SensitiveFieldProtector.encrypt(person.phoneNumber),
        eMail = SensitiveFieldProtector.encrypt(person.eMail),
        safeWord = SensitiveFieldProtector.encrypt(person.safeWord),
    )

    private fun decryptPerson(person: PersonData): PersonData = person.copy(
        phoneNumber = SensitiveFieldProtector.decrypt(person.phoneNumber),
        eMail = SensitiveFieldProtector.decrypt(person.eMail),
        safeWord = SensitiveFieldProtector.decrypt(person.safeWord),
    )

    private fun isPersonEncrypted(person: PersonData): Boolean {
        return SensitiveFieldProtector.isEncrypted(person.phoneNumber) &&
            SensitiveFieldProtector.isEncrypted(person.eMail) &&
            SensitiveFieldProtector.isEncrypted(person.safeWord)
    }
}
