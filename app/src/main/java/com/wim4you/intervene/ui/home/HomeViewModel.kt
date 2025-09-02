package com.wim4you.intervene.ui.home

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Firebase
import com.google.firebase.database.database
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.fbdata.DistressData
import com.wim4you.intervene.fbdata.LocationData
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HomeViewModel(
    private val personDataRepository: PersonDataRepository,
    private val vigilanteDataRepository: VigilanteDataRepository

) : ViewModel() {
    private val database = Firebase.database.getReference()
    // LiveData for distress notification status (for Toast)
    private val _distressStatus = MutableLiveData<String>()
    val distressStatus: LiveData<String> = _distressStatus

    // LiveData for current location (for map marker)
    private val _currentLocation = MutableLiveData<LatLng?>()
    val currentLocation: LiveData<LatLng?> = _currentLocation

    fun onStartPatrollingButtonClicked(activity:Activity){
        viewModelScope.launch {
            val vigilanteData = vigilanteDataRepository.fetch()
            if (vigilanteData == null) {
                Log.e("Room", "Failed to fetch Vigilante")
                _distressStatus.postValue("Failed to get vigilante data")
                return@launch
            }

            val location = LocationData(
                id = vigilanteData.id,
                vigilanteId = vigilanteData.id,
                IsActive = true
            )

            LocationUtils.getLocation(activity) { currentLatLng ->
                currentLatLng?.let {
                    location.location =
                        mapOf("latitude" to it.latitude, "longitude" to it.longitude)
                    sendPatrollingNotification(location)
                } ?: run {
                    _distressStatus.postValue("Failed to get location")
                }
            }
        }
    }

    fun onPanicButtonClicked(activity: Activity) {
        viewModelScope.launch {
            val personData = personDataRepository.fetch()
            if (personData == null) {
                Log.e("Room", "Failed to fetch PersonData")
                _distressStatus.postValue("Failed to get person data")
                return@launch
            }

            val distressData = DistressData(
                id = personData.id,
                personId = personData.id,
            )

            LocationUtils.getLocation(activity) { currentLatLng ->
                currentLatLng?.let {
                    distressData.location = mapOf("latitude" to it.latitude, "longitude" to it.longitude)
                    sendDistressNotification(distressData)
                } ?: run {
                    _distressStatus.postValue("Failed to get location")
                }
            }
        }
    }

    private fun sendPatrollingNotification(location: LocationData) {
        database.child("vigilante").child(location.id).setValue(location).addOnSuccessListener {
            Log.e("Firebase", "Success saving distress:")
        }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error saving distress: ${e.message}")
            }
    }
    private fun sendDistressNotification(distressData: DistressData) {
        // Format distress message
        //val id = database.child("distress").push().key ?: ""
        _distressStatus.postValue("Sending distress notification...") // Notify fragment to show Toast
        database.child("distress").child(distressData.id).setValue(distressData).addOnSuccessListener {
            Log.e("Firebase", "Success saving distress:")
        }
        .addOnFailureListener { e ->
            Log.e("Firebase", "Error saving distress: ${e.message}")
        }

        val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 4000) // Play for 4 seconds
        toneGen.release()
    }
}