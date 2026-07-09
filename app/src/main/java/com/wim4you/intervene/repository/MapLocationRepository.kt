package com.wim4you.intervene.repository

import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapLocationRepository @Inject constructor() {

    private val _patrolLocations = MutableStateFlow<List<PatrolLocationData>>(emptyList())
    private val _distressLocations = MutableStateFlow<List<DistressLocationData>>(emptyList())
    private val _distressCalls = MutableStateFlow<List<DistressCallData>>(emptyList())

    val patrolLocations: StateFlow<List<PatrolLocationData>> = _patrolLocations.asStateFlow()
    val distressLocations: StateFlow<List<DistressLocationData>> = _distressLocations.asStateFlow()
    val distressCalls: StateFlow<List<DistressCallData>> = _distressCalls.asStateFlow()

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
        longitude = longitude,
    )
}
