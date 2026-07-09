package com.wim4you.intervene.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.repository.MapLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Activity-scoped map data fed by [com.wim4you.intervene.location.LocationTrackerService]
 * through [MapLocationRepository].
 */
@HiltViewModel
class MapDataViewModel @Inject constructor(
    mapLocationRepository: MapLocationRepository,
) : ViewModel() {

    val patrolLocations: StateFlow<List<PatrolLocationData>> =
        mapLocationRepository.patrolLocations.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val distressLocations: StateFlow<List<DistressLocationData>> =
        mapLocationRepository.distressLocations.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val distressCalls: StateFlow<List<DistressCallData>> =
        mapLocationRepository.distressCalls.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}
