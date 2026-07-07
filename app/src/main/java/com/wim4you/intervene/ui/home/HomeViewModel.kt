package com.wim4you.intervene.ui.home

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.repository.PersonDataRepository
import kotlinx.coroutines.launch

class HomeViewModel(
    private val personDataRepository: PersonDataRepository,
) : ViewModel() {

    private val _distressMessage = MutableLiveData<String>()
    val distressStatus: LiveData<String> = _distressMessage

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
}
