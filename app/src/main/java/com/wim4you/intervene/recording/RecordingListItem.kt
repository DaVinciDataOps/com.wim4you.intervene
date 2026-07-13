package com.wim4you.intervene.recording

sealed class RecordingListItem {
    abstract val sortKey: Long

    data class SingleRecording(
        val relativePath: String,
        val username: String,
        val createdAtMillis: Long,
        val durationMillis: Long?,
    ) : RecordingListItem() {
        override val sortKey: Long = createdAtMillis
    }

    data class DistressSession(
        val sessionPath: String,
        val username: String,
        val startedAtMillis: Long,
        val segmentCount: Int,
    ) : RecordingListItem() {
        override val sortKey: Long = startedAtMillis
    }
}
