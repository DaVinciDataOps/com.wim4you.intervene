package com.wim4you.intervene.ui.distresscall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.helpers.DistanceUtils
import com.wim4you.intervene.helpers.ElapsedTimeFormatter
import com.wim4you.intervene.repository.MapLocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DistressCallItem(
    val call: DistressCallData,
    val distanceMeters: Double?,
    val elapsedSeconds: Long,
)

@HiltViewModel
class DistressListViewModel @Inject constructor(
    mapLocationRepository: MapLocationRepository,
) : ViewModel() {

    private val _userLatitude = MutableStateFlow<Double?>(null)
    private val _userLongitude = MutableStateFlow<Double?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val distressItems: StateFlow<List<DistressCallItem>> = combine(
        mapLocationRepository.distressCalls,
        _userLatitude,
        _userLongitude,
    ) { calls, userLat, userLng ->
        calls.map { call ->
            val distance = if (
                userLat != null && userLng != null &&
                call.latitude != null && call.longitude != null
            ) {
                DistanceUtils.metersBetween(userLat, userLng, call.latitude, call.longitude)
            } else {
                null
            }
            DistressCallItem(
                call = call,
                distanceMeters = distance,
                elapsedSeconds = ElapsedTimeFormatter.elapsedSecondsSince(call.startTime),
            )
        }.sortedWith(
            compareBy<DistressCallItem> { it.distanceMeters ?: Double.MAX_VALUE }
                .thenByDescending { it.elapsedSeconds },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun updateUserLocation(latitude: Double?, longitude: Double?) {
        _userLatitude.value = latitude
        _userLongitude.value = longitude
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _isRefreshing.value = false
        }
    }
}
