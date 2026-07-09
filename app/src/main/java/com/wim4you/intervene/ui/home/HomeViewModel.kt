package com.wim4you.intervene.ui.home

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.repository.DestinationHistoryRepository
import com.wim4you.intervene.repository.DestinationSuggestion
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.route.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val personDataRepository: PersonDataRepository,
    private val routeRepository: RouteRepository,
    private val destinationHistoryRepository: DestinationHistoryRepository,
) : ViewModel() {

    private val _distressMessage = MutableStateFlow<String?>(null)
    val distressStatus: StateFlow<String?> = _distressMessage.asStateFlow()

    private val _routeState = MutableStateFlow<RouteState>(RouteState.Idle)
    val routeState: StateFlow<RouteState> = _routeState.asStateFlow()

    private val _destinationSuggestions = MutableStateFlow<List<DestinationSuggestion>>(emptyList())
    val destinationSuggestions: StateFlow<List<DestinationSuggestion>> = _destinationSuggestions.asStateFlow()

    private var panicButtonPressCount = 0
    private val panicButtonPressWindowMs = 5000L
    private var lastPressTime = 0L

    fun onPanicButtonClicked(activity: Activity) {
        viewModelScope.launch {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastPressTime > panicButtonPressWindowMs) {
                panicButtonPressCount = 0
            }

            panicButtonPressCount++
            lastPressTime = currentTime

            val requiredPresses = 3
            if (panicButtonPressCount < requiredPresses) {
                if (panicButtonPressCount == 1) {
                    _distressMessage.value = "Press $requiredPresses times to activate distress"
                }
                return@launch
            }

            panicButtonPressCount = 0

            val personData = personDataRepository.fetch()
            if (personData == null || !AppModeController.isGuidedTrip) {
                _distressMessage.value = "Failed to get person data"
                return@launch
            }

            AppModeController.activateDistress(activity)
            _distressMessage.value = "Sending distress notification..."
        }
    }

    fun planRoute(destinationAddress: String, origin: LatLng) {
        if (!AppModeController.isGuidedTrip) return

        viewModelScope.launch {
            _routeState.value = RouteState.Loading

            val destination = routeRepository.geocodeAddress(destinationAddress.trim())
            if (destination == null) {
                _routeState.value = RouteState.Error("Could not find that destination")
                return@launch
            }

            routeRepository.fetchRoute(origin, destination)
                .onSuccess { route ->
                    destinationHistoryRepository.recordUsage(destinationAddress)
                    _routeState.value = RouteState.Success(
                        points = route.points,
                        destination = route.destination,
                        summary = "${route.durationText} · ${route.distanceText}",
                    )
                    loadDestinationSuggestions(destinationAddress)
                }
                .onFailure { error ->
                    _routeState.value = RouteState.Error(
                        error.message ?: "Could not fetch route",
                    )
                }
        }
    }

    fun loadDestinationSuggestions(query: String) {
        viewModelScope.launch {
            _destinationSuggestions.value = destinationHistoryRepository.getRankedSuggestions(query)
        }
    }

    fun clearRoute() {
        _routeState.value = RouteState.Idle
    }
}
