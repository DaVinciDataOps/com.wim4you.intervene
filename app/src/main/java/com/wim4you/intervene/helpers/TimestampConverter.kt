package com.wim4you.intervene.helpers

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


object TimestampConverter {
    // Convert timestamp to date and time
    fun toDateTime(timestamp: Long): String {
        val dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        )
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return dateTime.format(formatter)
    }

    // Convert timestamp to time only
    fun toTime(timestamp: Long?): String {
        if(timestamp == null)
            return "N/A"

        val dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        )
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        return dateTime.format(formatter)
    }

    fun lapSeconds(startTimestamp: Long?, endTimestamp: Long?): Long {
        if (startTimestamp == null || endTimestamp == null) {
            return 0L
        }
        val startInstant = Instant.ofEpochMilli(startTimestamp)
        val endInstant = Instant.ofEpochMilli(endTimestamp)
        return Duration.between(startInstant, endInstant).seconds
    }
}