package com.wim4you.intervene.repository

import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.data.VigilanteData
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
    private var remotePatrols: List<PatrolLocationData> = emptyList()
    private var ownPatrol: PatrolLocationData? = null

    val patrolLocations: StateFlow<List<PatrolLocationData>> = _patrolLocations.asStateFlow()
    val distressLocations: StateFlow<List<DistressLocationData>> = _distressLocations.asStateFlow()
    val distressCalls: StateFlow<List<DistressCallData>> = _distressCalls.asStateFlow()

    fun updatePatrolLocations(locations: List<PatrolLocationData>) {
        remotePatrols = locations
        publishMergedPatrols()
    }

    fun setOwnPatrol(vigilante: VigilanteData, latitude: Double, longitude: Double) {
        ownPatrol = PatrolLocationData(
            id = vigilante.id,
            vigilanteId = vigilante.id,
            name = vigilante.name,
            l = listOf(latitude, longitude),
            time = System.currentTimeMillis(),
            isActive = true,
        )
        publishMergedPatrols()
    }

    fun updateOwnPatrolLocation(latitude: Double, longitude: Double) {
        val current = ownPatrol ?: return
        ownPatrol = current.copy(
            l = listOf(latitude, longitude),
            time = System.currentTimeMillis(),
        )
        publishMergedPatrols()
    }

    fun ensureOwnPatrol(vigilante: VigilanteData, latitude: Double, longitude: Double) {
        if (ownPatrol == null) {
            setOwnPatrol(vigilante, latitude, longitude)
        } else {
            updateOwnPatrolLocation(latitude, longitude)
        }
    }

    fun clearOwnPatrol() {
        ownPatrol = null
        publishMergedPatrols()
    }

    private fun publishMergedPatrols() {
        val own = ownPatrol
        _patrolLocations.value = if (own == null) {
            remotePatrols
        } else {
            remotePatrols.filter { it.vigilanteId != own.vigilanteId } + own
        }
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
