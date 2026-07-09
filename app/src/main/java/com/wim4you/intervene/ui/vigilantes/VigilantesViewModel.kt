package com.wim4you.intervene.ui.vigilantes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.repository.VigilanteDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VigilantesViewModel @Inject constructor(
    private val repository: VigilanteDataRepository,
) : ViewModel() {

    private val _recentData = MutableStateFlow<VigilanteData?>(null)
    val recentData: StateFlow<VigilanteData?> = _recentData.asStateFlow()

    fun fetchData() {
        viewModelScope.launch {
            val vigilante = repository.fetch()
            AppModeController.vigilante = vigilante
            _recentData.value = vigilante
        }
    }

    fun saveData(data: VigilanteData) {
        viewModelScope.launch {
            repository.upsert(data)
        }
    }
}
