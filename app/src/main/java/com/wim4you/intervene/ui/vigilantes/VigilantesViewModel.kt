package com.wim4you.intervene.ui.vigilantes

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import kotlinx.coroutines.launch

class VigilantesViewModel(private val repository: VigilanteDataRepository) : ViewModel() {

    private val _recentData = MutableLiveData<VigilanteData?>()
    val recentData: LiveData<VigilanteData?> get() = _recentData

    fun fetchData() {
        viewModelScope.launch {
            val person = repository.fetch()
            _recentData.postValue(person)
        }
    }

    fun saveData(data: VigilanteData)
    {
        viewModelScope.launch {
            repository.upsert(data)
        }
    }
}