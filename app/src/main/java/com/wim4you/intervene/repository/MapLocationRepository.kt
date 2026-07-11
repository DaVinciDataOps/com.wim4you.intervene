package com.wim4you.intervene.repository

import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.data.PersonData
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
    private var remoteDistress: List<DistressLocationData> = emptyList()
    private var ownDistress: DistressLocationData? = null

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
        remoteDistress = locations.filter { it.isActive == true }
        publishMergedDistress()
    }

    fun setOwnDistress(person: PersonData, latitude: Double, longitude: Double, firebaseUid: String? = null) {
        val startTime = System.currentTimeMillis()
        val distressId = firebaseUid ?: person.id
        ownDistress = DistressLocationData(
            id = distressId,
            personId = distressId,
            alias = person.alias,
            address = null,
            l = listOf(latitude, longitude),
            startTime = startTime,
            time = startTime,
            isActive = true,
        )
        publishMergedDistress()
    }

    fun updateOwnDistressLocation(latitude: Double, longitude: Double) {
        val current = ownDistress ?: return
        ownDistress = current.copy(
            l = listOf(latitude, longitude),
            time = System.currentTimeMillis(),
        )
        publishMergedDistress()
    }

    fun ensureOwnDistress(
        person: PersonData,
        latitude: Double,
        longitude: Double,
        firebaseUid: String? = null,
    ) {
        if (ownDistress == null) {
            setOwnDistress(person, latitude, longitude, firebaseUid)
        } else {
            updateOwnDistressLocation(latitude, longitude)
        }
    }

    fun clearOwnDistress() {
        ownDistress = null
        publishMergedDistress()
    }

    private fun publishMergedDistress() {
        val own = ownDistress?.takeIf { it.isActive == true }
        val activeRemote = remoteDistress.filter { it.isActive == true }
        val merged = if (own == null) {
            activeRemote
        } else {
            val ownId = own.id ?: own.personId
            activeRemote.filter { (it.id ?: it.personId) != ownId } + own
        }
        _distressLocations.value = merged
        _distressCalls.value = merged.map { it.toDistressCallData() }
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
