package com.wim4you.intervene.ui.register

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.launch

class RegisterViewModel(private val repository: PersonDataRepository) : ViewModel() {
    private val _recentPerson = MutableLiveData<PersonData?>()
    val recentPerson: LiveData<PersonData?> get() = _recentPerson

    fun fetchPersonData() {
        viewModelScope.launch {
            val person = repository.fetch()
            _recentPerson.postValue(person)
        }
    }

    fun savePersonData(personData: PersonData)
        {
        viewModelScope.launch {
            repository.upsertPersonData(personData)
        }
    }

}