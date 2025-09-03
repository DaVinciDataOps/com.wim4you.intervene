package com.wim4you.intervene.ui.home

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Firebase
import com.google.firebase.database.database
import com.wim4you.intervene.AppState
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
    val distressStatus: LiveData<String> = _distressStatus
    val patrollingStatus: LiveData<String> = _patrollingStatus

    // LiveData for current location (for map marker)
    private val _currentLocation = MutableLiveData<LatLng?>()
    val currentLocation: LiveData<LatLng?> = _currentLocation

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
//            val distressData = DistressData(
//                id = personData.id,
//                personId = personData.id,
//            )

//            LocationUtils.getLocation(activity) { currentLatLng ->
//                currentLatLng?.let {
//                    distressData.location = mapOf("latitude" to it.latitude, "longitude" to it.longitude)
//                    sendDistressNotification(distressData)
//                } ?: run {
//                    _distressStatus.postValue("Failed to get location")
//                }
//            }
        }
    }

//    private fun sendDistressNotification(distressData: DistressData) {
//        // Format distress message
//        //val id = database.child("distress").push().key ?: ""
//        _distressStatus.postValue("Sending distress notification...") // Notify fragment to show Toast
//        database.child("distress").child(distressData.id).setValue(distressData).addOnSuccessListener {
//            Log.e("Firebase", "Success saving distress:")
//        }
//        .addOnFailureListener { e ->
//            Log.e("Firebase", "Error saving distress: ${e.message}")
//        }
//
//        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
//        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 4000) // Play for 4 seconds
//        toneGen.release()
//    }
}