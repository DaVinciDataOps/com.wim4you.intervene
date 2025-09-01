package com.wim4you.intervene.ui.vigilantes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wim4you.intervene.repository.VigilanteDataRepository
import com.wim4you.intervene.ui.register.RegisterViewModel

class VigilantesViewModelFactory(private val repository: VigilanteDataRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RegisterViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VigilantesViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}