package com.wim4you.intervene.helpers

object ElapsedTimeFormatter {

    fun formatElapsedSeconds(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (remainingMinutes == 0L) "${hours}h" else "${hours}h ${remainingMinutes}m"
    }

    fun elapsedSecondsSince(startTimeMillis: Long?): Long {
        if (startTimeMillis == null) return 0L
        val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
        return elapsed.coerceAtLeast(0)
    }
}
