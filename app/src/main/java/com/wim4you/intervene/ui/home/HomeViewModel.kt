package com.wim4you.intervene.ui.home

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.FirebaseAuthManager
import com.wim4you.intervene.R
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.helpers.NetworkUtils
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.distress.DistressFirebaseWriter
import com.wim4you.intervene.repository.DestinationHistoryRepository
import com.wim4you.intervene.repository.DestinationSuggestion
import com.wim4you.intervene.repository.MapLocationRepository
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.route.RouteRepository
import com.wim4you.intervene.ui.common.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PanicButtonState(
    val pressesRemaining: Int = 3,
    val isActive: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val personDataRepository: PersonDataRepository,
    private val routeRepository: RouteRepository,
    private val destinationHistoryRepository: DestinationHistoryRepository,
    private val mapLocationRepository: MapLocationRepository,
) : ViewModel() {

    private val _distressMessage = MutableStateFlow<UiMessage?>(null)
    val distressMessage: StateFlow<UiMessage?> = _distressMessage.asStateFlow()

    private val _panicButtonState = MutableStateFlow(PanicButtonState())
    val panicButtonState: StateFlow<PanicButtonState> = _panicButtonState.asStateFlow()

    private val _routeState = MutableStateFlow<RouteState>(RouteState.Idle)
    val routeState: StateFlow<RouteState> = _routeState.asStateFlow()

    private val _destinationSuggestions = MutableStateFlow<List<DestinationSuggestion>>(emptyList())
    val destinationSuggestions: StateFlow<List<DestinationSuggestion>> = _destinationSuggestions.asStateFlow()

    private var panicButtonPressCount = 0
    private val panicButtonPressWindowMs = 5000L
    private var lastPressTime = 0L
    private val requiredPresses = 3

    fun onPanicButtonClicked(activity: Activity) {
        viewModelScope.launch {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastPressTime > panicButtonPressWindowMs) {
                panicButtonPressCount = 0
            }

            panicButtonPressCount++
            lastPressTime = currentTime

            val remaining = (requiredPresses - panicButtonPressCount).coerceAtLeast(0)
            _panicButtonState.value = PanicButtonState(
                pressesRemaining = remaining,
                isActive = remaining > 0,
            )

            if (panicButtonPressCount < requiredPresses) {
                if (panicButtonPressCount == 1) {
                    _distressMessage.value = UiMessage.Resource(
                        R.string.panic_press_remaining,
                        listOf(requiredPresses),
                    )
                }
                return@launch
            }

            panicButtonPressCount = 0
            _panicButtonState.value = PanicButtonState()

            if (!NetworkUtils.isOnline(activity)) {
                _distressMessage.value = UiMessage.Resource(R.string.error_no_network_distress)
                return@launch
            }

            val personData = personDataRepository.fetch()
            if (personData == null || !AppModeController.isGuidedTrip) {
                _distressMessage.value = UiMessage.Resource(R.string.panic_person_data_missing)
                return@launch
            }

            AppModeController.person = personData
            AppModeController.activateDistress(activity)
            publishAndPushDistress(activity, personData)
            _distressMessage.value = UiMessage.Resource(R.string.panic_sending_distress)
        }
    }

    private fun publishAndPushDistress(activity: Activity, personData: PersonData) {
        LocationUtils.resolveLocation(activity) { latLng ->
            if (latLng == null) return@resolveLocation
            viewModelScope.launch {
                val firebaseUid = try {
                    FirebaseAuthManager.ensureSignedIn()
                } catch (exception: Exception) {
                    _distressMessage.value = UiMessage.Resource(R.string.error_no_network_distress)
                    return@launch
                }
                mapLocationRepository.setOwnDistress(
                    person = personData,
                    latitude = latLng.latitude,
                    longitude = latLng.longitude,
                    firebaseUid = firebaseUid,
                )
                try {
                    DistressFirebaseWriter.pushDistress(
                        personData = personData,
                        latitude = latLng.latitude,
                        longitude = latLng.longitude,
                        init = true,
                    )
                } catch (exception: Exception) {
                    _distressMessage.value = UiMessage.Resource(R.string.error_no_network_distress)
                }
            }
        }
    }

    fun clearDistressMessage() {
        _distressMessage.value = null
    }

    fun planRoute(destinationAddress: String, origin: LatLng, isOnline: Boolean) {
        if (!AppModeController.isGuidedTrip) return

        viewModelScope.launch {
            if (!isOnline) {
                _routeState.value = RouteState.Error(UiMessage.Resource(R.string.error_no_network_route))
                return@launch
            }

            _routeState.value = RouteState.Loading

            val destination = routeRepository.geocodeAddress(destinationAddress.trim())
            if (destination == null) {
                _routeState.value = RouteState.Error(UiMessage.Resource(R.string.route_destination_not_found))
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
                        UiMessage.Resource(R.string.route_fetch_failed),
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
