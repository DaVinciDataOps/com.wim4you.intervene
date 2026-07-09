package com.wim4you.intervene.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.repository.InterveningRepository
import com.wim4you.intervene.repository.MapLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity-scoped map data fed by [com.wim4you.intervene.location.LocationTrackerService]
 * through [MapLocationRepository].
 */
@HiltViewModel
class MapDataViewModel @Inject constructor(
    private val mapLocationRepository: MapLocationRepository,
    private val interveningRepository: InterveningRepository,
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

    private val _selectedDistressId = MutableStateFlow<String?>(null)
    val selectedDistressId: StateFlow<String?> = _selectedDistressId.asStateFlow()

    private val _verifiedDistressIds = MutableStateFlow<Set<String>>(emptySet())
    val verifiedDistressIds: StateFlow<Set<String>> = _verifiedDistressIds.asStateFlow()

    private val _interveningDistressIds = MutableStateFlow<Set<String>>(emptySet())
    val interveningDistressIds: StateFlow<Set<String>> = _interveningDistressIds.asStateFlow()

    private val _interventionMessage = MutableStateFlow<String?>(null)
    val interventionMessage: StateFlow<String?> = _interventionMessage.asStateFlow()

    fun isDistressVerified(distressId: String): Boolean =
        distressId in _verifiedDistressIds.value

    fun isIntervening(distressId: String): Boolean =
        distressId in _interveningDistressIds.value

    fun focusDistress(distressId: String) {
        _selectedDistressId.value = distressId
    }

    fun clearFocusedDistress() {
        _selectedDistressId.value = null
    }

    fun clearInterventionMessage() {
        _interventionMessage.value = null
    }

    fun verifyAndIntervene(
        distressCall: DistressCallData,
        safeWord: String,
        vigilante: VigilanteData,
        onVerified: () -> Unit,
    ) {
        val distressId = distressCall.id ?: return
        viewModelScope.launch {
            val verified = interveningRepository.verifySafeWord(distressId, safeWord)
            if (!verified) {
                _interventionMessage.value = "safe_word_incorrect"
                return@launch
            }
            _verifiedDistressIds.value = _verifiedDistressIds.value + distressId
            val result = interveningRepository.registerIntervention(distressId, vigilante)
            if (result.isSuccess) {
                _interveningDistressIds.value = _interveningDistressIds.value + distressId
                _selectedDistressId.value = distressId
                _interventionMessage.value = "intervention_registered"
                onVerified()
            } else {
                _interventionMessage.value = "intervention_failed"
            }
        }
    }
}
