package com.wim4you.intervene.liverecording

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class LiveRecordingListViewModel @Inject constructor() : ViewModel() {
    private val _recordings = MutableStateFlow<List<LiveRecordingEntry>>(emptyList())
    val recordings: StateFlow<List<LiveRecordingEntry>> = _recordings.asStateFlow()

    fun refresh(context: android.content.Context) {
        _recordings.value = LiveRecordingLocalStore.listRecordings(context)
    }

    fun deleteRecording(context: android.content.Context, entry: LiveRecordingEntry) {
        LiveRecordingLocalStore.deleteRecording(context, entry)
        refresh(context)
    }
}
