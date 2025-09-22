package com.wim4you.intervene.ui.distresscall

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.wim4you.intervene.data.DistressCallData

class DistressListViewModel : ViewModel() {

    private val _distressCalls = MutableLiveData<List<DistressCallData>>()
    val distressCalls: LiveData<List<DistressCallData>> get() = _distressCalls

    init {
        // Load your existing list here; replace with actual source if dynamic
        _distressCalls.value = emptyList()
    }

    fun updateDistressCalls(newList: List<DistressCallData>) {
        _distressCalls.value = newList
    }

    private val _text = MutableLiveData<String>().apply {
        value = "This is slideshow Fragment"
    }
    val text: LiveData<String> = _text
}