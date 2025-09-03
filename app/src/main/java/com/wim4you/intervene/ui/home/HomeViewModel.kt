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
    private val _distressStatus = MutableLiveData<String>()
    private val _patrollingStatus = MutableLiveData<String>()

    private val _patrolLocations = MutableLiveData<List<PatrolData>>(emptyList())
    val patrolLocations: LiveData<List<PatrolData>> = _patrolLocations
    val distressStatus: LiveData<String> = _distressStatus
    val patrollingStatus: LiveData<String> = _patrollingStatus

    // LiveData for current location (for map marker)
    private val _currentLocation = MutableLiveData<LatLng?>()
    val currentLocation: LiveData<LatLng?> = _currentLocation

    // Register this in the Fragment to receive updates
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_LOCATION_UPDATE) {
                val patrolDataList = intent.getParcelableArrayListExtra<PatrolData>(LocationService.EXTRA_PATROL_DATA)
                _patrolLocations.value = patrolDataList ?: emptyList()
            }
        }
    }

    fun registerLocationReceiver(context: Context) {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(locationReceiver,
                IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
            )
    }

    fun unregisterLocationReceiver(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(locationReceiver)
    }

    fun updateTripState(activity: Activity, isDistressState: Boolean) {
        AppState.isDistressState = isDistressState
        val intent = Intent(activity, TripService::class.java)
        if (isDistressState) {
            activity.startService(intent)
        } else {
            activity.stopService(intent)
        }
    }

    fun onPanicButtonClicked(activity: Activity) {
        viewModelScope.launch {
            val personData = personDataRepository.fetch()
            if (personData == null || !AppState.isGuidedTrip) {
                Log.e("Room", "Failed to fetch PersonData")
                _distressStatus.postValue("Failed to get person data")
                return@launch
            }
            AppState.isDistressState = true
            updateTripState(activity, AppState.isDistressState)
            _distressStatus.postValue("Sending distress notification...")
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