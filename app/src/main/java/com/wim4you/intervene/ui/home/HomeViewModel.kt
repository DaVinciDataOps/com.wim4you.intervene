package com.wim4you.intervene.ui.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.AppState
import com.wim4you.intervene.distress.DistressSoundService
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.location.LocationTrackerService
import com.wim4you.intervene.distress.DistressService
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.launch

class HomeViewModel(
    private val personDataRepository: PersonDataRepository,

) : ViewModel() {

    // LiveData for distress notification status (for Toast)
    private val _distressMessage = MutableLiveData<String>()

    private val _patrolLocations = MutableLiveData<List<PatrolLocationData>>(emptyList())
    private val _distressLocations = MutableLiveData<List<DistressLocationData>>(emptyList())
    val patrolLocations: LiveData<List<PatrolLocationData>> = _patrolLocations
    val distressLocations: LiveData<List<DistressLocationData>> = _distressLocations
    val distressStatus: LiveData<String> = _distressMessage

    private var panicButtonPressCount = 0
    private val panicButtonPressWindowMs = 5000L // 5 seconds time window for presses
    private var lastPressTime = 0L

    // Register this in the Fragment to receive updates
    private val locationTrackerServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackerService.ACTION_PATROL_UPDATE) {
                val patrolLocationDataList = intent.getParcelableArrayListExtra<PatrolLocationData>(LocationTrackerService.EXTRA_PATROL_DATA)
                _patrolLocations.value = patrolLocationDataList ?: emptyList()
            }
            if (intent?.action == LocationTrackerService.ACTION_DISTRESS_UPDATE) {
                val distressDataList = intent.getParcelableArrayListExtra<DistressLocationData>(LocationTrackerService.EXTRA_DISTRESS_DATA)
                _distressLocations.value = distressDataList ?: emptyList()
            }
        }
    }

    fun registerLocationTrackerReceiver(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(locationTrackerServiceReceiver,
                IntentFilter(LocationTrackerService.ACTION_PATROL_UPDATE)
            )
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(locationTrackerServiceReceiver,
                IntentFilter(LocationTrackerService.ACTION_DISTRESS_UPDATE)
            )
    }

    fun updateTripState(tripActivity: Activity, isDistressState: Boolean) {
        AppState.isDistressState = isDistressState
        val intent = Intent(tripActivity, DistressService::class.java)
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
                if(panicButtonPressCount == 1)
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
}