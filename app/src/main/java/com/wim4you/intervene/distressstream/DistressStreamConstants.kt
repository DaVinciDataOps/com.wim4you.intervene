package com.wim4you.intervene.distressstream

object DistressStreamConstants {
    const val RTDB_ROOT = "distress_streams"
    const val STORAGE_ROOT = "distress_streams"
    const val ARG_DISTRESS_ID = "distress_id"
    const val ARG_DISTRESS_ALIAS = "distress_alias"
    const val ARG_SESSION_ID = "session_id"
    const val SEGMENT_DURATION_MS = 4_000L
    const val MAX_LIVE_SEGMENT_AGE_MS = 10 * 60 * 1000L
}
