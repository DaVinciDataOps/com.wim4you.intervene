package com.wim4you.intervene.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.repository.PersonDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val repository: PersonDataRepository,
) : ViewModel() {

    private val _recentPerson = MutableStateFlow<PersonData?>(null)
    val recentPerson: StateFlow<PersonData?> = _recentPerson.asStateFlow()

    fun fetchPersonData() {
        viewModelScope.launch {
            _recentPerson.value = repository.fetch()
        }
    }

    fun savePersonData(personData: PersonData) {
        viewModelScope.launch {
            repository.upsertPersonData(personData)
        }
    }
}
