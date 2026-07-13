package com.wim4you.intervene.distressstream

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DistressRecordingListViewModel @Inject constructor() : ViewModel() {
    private val _sessions = MutableStateFlow<List<DistressRecordingSession>>(emptyList())
    val sessions: StateFlow<List<DistressRecordingSession>> = _sessions.asStateFlow()

    fun refresh(context: android.content.Context) {
        _sessions.value = DistressRecordingLocalStore.listSessions(context)
    }

    fun deleteSession(context: android.content.Context, session: DistressRecordingSession) {
        DistressRecordingLocalStore.deleteSession(context, session)
        refresh(context)
    }
}
