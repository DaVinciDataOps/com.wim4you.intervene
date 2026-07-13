package com.wim4you.intervene.liverecording

data class LiveRecordingEntry(
    val id: String,
    val filename: String,
    val createdAtMillis: Long,
    val durationMillis: Long?,
)
