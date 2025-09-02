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
import com.wim4you.intervene.location.LocationUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HomeViewModel : ViewModel() {
    private val database = Firebase.database.getReference()
    // LiveData for distress notification status (for Toast)
    private val _distressStatus = MutableLiveData<String>()
    val distressStatus: LiveData<String> = _distressStatus

    // LiveData for current location (for map marker)
    private val _currentLocation = MutableLiveData<LatLng?>()
    val currentLocation: LiveData<LatLng?> = _currentLocation

    fun onPanicButtonClicked(activity: Activity) {
        viewModelScope.launch {

            LocationUtils.getLocation(activity) { currentLatLng ->
                currentLatLng?.let {
                    sendDistressNotification(it)
                } ?: run {
                    _distressStatus.postValue("Failed to get location")
                }
            }
        }
    }

    private fun sendDistressNotification(loc: LatLng) {
        // Format distress message
        val message = "Distress location ${loc.latitude}: ${loc.longitude}"
        val id = database.child("distress").push().key ?: ""
        _distressStatus.postValue(message) // Notify fragment to show Toast

        database.child("distress").child(id).setValue(message).addOnSuccessListener {
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