package com.wim4you.intervene.distressstream

data class DistressStreamSegment(
    val segmentId: String,
    val downloadUrl: String,
    val uploadedAtMillis: Long,
)

data class DistressRecordingSession(
    val sessionId: String,
    val distressId: String,
    val distressAlias: String?,
    val startedAtMillis: Long,
    val segmentCount: Int,
)
