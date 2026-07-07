package com.wim4you.intervene.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wim4you.intervene.repository.DestinationHistoryRepository
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.route.RouteRepository

class HomeViewModelFactory(
    private val personDataRepository: PersonDataRepository,
    private val routeRepository: RouteRepository,
    private val destinationHistoryRepository: DestinationHistoryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(
                personDataRepository,
                routeRepository,
                destinationHistoryRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
