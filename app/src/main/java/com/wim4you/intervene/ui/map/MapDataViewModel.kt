package com.wim4you.intervene.ui.map

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData

/**
 * Activity-scoped map data fed by [com.wim4you.intervene.location.LocationTrackerService]
 * via a single broadcast receiver in MainActivity.
 */
class MapDataViewModel : ViewModel() {

    private val _patrolLocations = MutableLiveData<List<PatrolLocationData>>(emptyList())
    private val _distressLocations = MutableLiveData<List<DistressLocationData>>(emptyList())
    private val _distressCalls = MutableLiveData<List<DistressCallData>>(emptyList())

    val patrolLocations: LiveData<List<PatrolLocationData>> = _patrolLocations
    val distressLocations: LiveData<List<DistressLocationData>> = _distressLocations
    val distressCalls: LiveData<List<DistressCallData>> = _distressCalls

    fun updatePatrolLocations(locations: List<PatrolLocationData>) {
        _patrolLocations.value = locations
    }

    fun updateDistressLocations(locations: List<DistressLocationData>) {
        _distressLocations.value = locations
        _distressCalls.value = locations.map { it.toDistressCallData() }
    }

    private fun DistressLocationData.toDistressCallData() = DistressCallData(
        id = id ?: personId,
        alias = alias,
        address = address,
        startTime = startTime,
        latitude = latitude,
        longitude = longitude
    )
}
