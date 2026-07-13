package com.wim4you.intervene.recording

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RecordingListViewModel @Inject constructor() : ViewModel() {
    private val _recordings = MutableStateFlow<List<RecordingListItem>>(emptyList())
    val recordings: StateFlow<List<RecordingListItem>> = _recordings.asStateFlow()

    fun refresh(context: android.content.Context) {
        _recordings.value = RecordingLocalStore.listAll(context)
    }

    fun deleteItem(context: android.content.Context, item: RecordingListItem) {
        RecordingLocalStore.deleteItem(context, item)
        refresh(context)
    }
}
