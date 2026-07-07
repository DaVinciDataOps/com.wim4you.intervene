package com.wim4you.intervene.ui.home

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.route.RouteRepository
import kotlinx.coroutines.launch

class HomeViewModel(
    private val personDataRepository: PersonDataRepository,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val _distressMessage = MutableLiveData<String>()
    val distressStatus: LiveData<String> = _distressMessage

    private val _routeState = MutableLiveData<RouteState>(RouteState.Idle)
    val routeState: LiveData<RouteState> = _routeState

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
                    _distressMessage.postValue("Press $requiredPresses times to activate distress")
                }
                return@launch
            }

            panicButtonPressCount = 0

            val personData = personDataRepository.fetch()
            if (personData == null || !AppModeController.isGuidedTrip) {
                _distressMessage.postValue("Failed to get person data")
                return@launch
            }

            AppModeController.activateDistress(activity)
            _distressMessage.postValue("Sending distress notification...")
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
                    _routeState.value = RouteState.Success(
                        points = route.points,
                        destination = route.destination,
                        summary = "${route.durationText} · ${route.distanceText}",
                    )
                }
                .onFailure { error ->
                    _routeState.value = RouteState.Error(
                        error.message ?: "Could not fetch route"
                    )
                }
        }
    }

    fun clearRoute() {
        _routeState.value = RouteState.Idle
    }
}
