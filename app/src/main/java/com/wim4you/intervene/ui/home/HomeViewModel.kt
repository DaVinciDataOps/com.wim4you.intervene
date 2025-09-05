package com.wim4you.intervene.ui.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Firebase
import com.google.firebase.database.database
import com.wim4you.intervene.AppState
import com.wim4you.intervene.distress.DistressSoundService
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolData
import com.wim4you.intervene.location.LocationService
import com.wim4you.intervene.location.TripService
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import kotlinx.coroutines.launch

class HomeViewModel(
    private val personDataRepository: PersonDataRepository,
    private val vigilanteDataRepository: VigilanteDataRepository

) : ViewModel() {
    private val database = Firebase.database.getReference()
    // LiveData for distress notification status (for Toast)
    private val _distressMessage = MutableLiveData<String>()
    private val _patrollingStatus = MutableLiveData<String>()

    private val _patrolLocations = MutableLiveData<List<PatrolData>>(emptyList())
    private val _distressLocations = MutableLiveData<List<DistressLocationData>>(emptyList())
    val patrolLocations: LiveData<List<PatrolData>> = _patrolLocations
    val distressLocations: LiveData<List<DistressLocationData>> = _distressLocations
    val distressStatus: LiveData<String> = _distressMessage
    val patrollingStatus: LiveData<String> = _patrollingStatus

    private var panicButtonPressCount = 0
    private val panicButtonPressWindowMs = 5000L // 5 seconds time window for presses
    private var lastPressTime = 0L

    // LiveData for current location (for map marker)
    private val _currentLocation = MutableLiveData<LatLng?>()
    val currentLocation: LiveData<LatLng?> = _currentLocation

    // Register this in the Fragment to receive updates
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_PATROL_UPDATE) {
                val patrolDataList = intent.getParcelableArrayListExtra<PatrolData>(LocationService.EXTRA_PATROL_DATA)
                _patrolLocations.value = patrolDataList ?: emptyList()
            }
            if (intent?.action == LocationService.ACTION_DISTRESS_UPDATE) {
                val distressDataList = intent.getParcelableArrayListExtra<DistressLocationData>(LocationService.EXTRA_DISTRESS_DATA)
                _distressLocations.value = distressDataList ?: emptyList()
            }
        }
    }

    fun registerLocationReceiver(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(locationReceiver,
                IntentFilter(LocationService.ACTION_PATROL_UPDATE)
            )
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(locationReceiver,
                IntentFilter(LocationService.ACTION_DISTRESS_UPDATE)
            )
    }

    fun unregisterLocationReceiver(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(locationReceiver)
    }

    fun updateTripState(tripActivity: Activity, isDistressState: Boolean) {
        AppState.isDistressState = isDistressState
        val intent = Intent(tripActivity, TripService::class.java)
        if (isDistressState) {
            tripActivity.startService(intent)
        } else {
            tripActivity.stopService(intent)
        }
    }

    fun onPanicButtonClicked(activity: Activity) {
        viewModelScope.launch {
            val currentTime = System.currentTimeMillis()

            // Check if the press is within the time window
            if (currentTime - lastPressTime > panicButtonPressWindowMs) {
                // Reset counter if the time window has expired
                panicButtonPressCount = 0
            }

            // Increment press count
            panicButtonPressCount++
            lastPressTime = currentTime

            // Require 2 or 3 presses (adjust to 2 or 3 as needed)
            val requiredPresses = 3 // Change to 3 if you want 3 presses
            if (panicButtonPressCount < requiredPresses) {
                _distressMessage.postValue("Press $requiredPresses times to activate distress")
                return@launch
            }

            // Reset counter after activation
            panicButtonPressCount = 0

            val personData = personDataRepository.fetch()
            if (personData == null || !AppState.isGuidedTrip) {
                Log.e("Room", "Failed to fetch PersonData")
                _distressMessage.postValue("Failed to get person data")
                return@launch
            }
            AppState.isDistressState = true
            updateTripState(activity, AppState.isDistressState)
            _distressMessage.postValue("Sending distress notification...")
            DistressSoundService.start(activity)
        }
    }

      // Start LocationService (call from Fragment when needed)
    fun startLocationService(context: Context) {
        val intent = Intent(context, LocationService::class.java)
        context.startService(intent)
    }

    // Stop LocationService (call when Fragment is destroyed or as needed)
    fun stopLocationService(context: Context) {
        val intent = Intent(context, LocationService::class.java)
        context.stopService(intent)
    }

}