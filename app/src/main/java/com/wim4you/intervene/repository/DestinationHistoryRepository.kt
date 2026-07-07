package com.wim4you.intervene.repository

import com.wim4you.intervene.dao.DestinationHistoryDao
import com.wim4you.intervene.data.DestinationHistory
import com.wim4you.intervene.route.DestinationHistoryRanker
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DestinationSuggestion(
  val address: String,
  val usedAt: Long,
  val usedAtLabel: String,
)

class DestinationHistoryRepository(
  private val destinationHistoryDao: DestinationHistoryDao,
) {
  suspend fun recordUsage(address: String) {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) return

    destinationHistoryDao.insert(
      DestinationHistory(
        address = trimmed,
        usedAt = System.currentTimeMillis(),
      )
    )
    destinationHistoryDao.trimToMaxEntries()
  }

  suspend fun getRankedSuggestions(query: String, limit: Int = 20): List<DestinationSuggestion> {
    val now = System.currentTimeMillis()
    val normalizedQuery = query.trim().lowercase()
    val entries = destinationHistoryDao.getRecent()
      .filter { normalizedQuery.isEmpty() || it.address.lowercase().contains(normalizedQuery) }

    return entries
      .groupBy { it.address.lowercase() }
      .mapNotNull { (_, historyEntries) ->
        historyEntries.minByOrNull { DestinationHistoryRanker.score(it.usedAt, now) }
      }
      .sortedBy { DestinationHistoryRanker.score(it.usedAt, now) }
      .take(limit)
      .map { entry ->
        DestinationSuggestion(
          address = entry.address,
          usedAt = entry.usedAt,
          usedAtLabel = formatUsedAt(entry.usedAt),
        )
      }
  }

  private fun formatUsedAt(timestamp: Long): String {
    val dateTime = LocalDateTime.ofInstant(
      Instant.ofEpochMilli(timestamp),
      ZoneId.systemDefault(),
    )
    val formatter = DateTimeFormatter.ofPattern("EEE HH:mm, d MMM yyyy", Locale.getDefault())
    return dateTime.format(formatter)
  }
}
