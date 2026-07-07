package com.wim4you.intervene.route

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

object DestinationHistoryRanker {
  private const val MAX_HISTORY = 150

  fun score(usedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long {
    val entry = Calendar.getInstance().apply { timeInMillis = usedAtMillis }
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

    val dayDiff = cyclicDiff(
      entry.get(Calendar.DAY_OF_WEEK),
      now.get(Calendar.DAY_OF_WEEK),
      7,
    )
    val hourDiff = cyclicDiff(
      entry.get(Calendar.HOUR_OF_DAY),
      now.get(Calendar.HOUR_OF_DAY),
      24,
    )
    val minuteDiff = cyclicDiff(
      entry.get(Calendar.MINUTE),
      now.get(Calendar.MINUTE),
      60,
    )

    val patternMinutes = dayDiff * 24 * 60 + hourDiff * 60 + minuteDiff
    val ageDays = (nowMillis - usedAtMillis) / 86_400_000L

    return patternMinutes * 10_000L + ageDays
  }

  private fun cyclicDiff(a: Int, b: Int, modulus: Int): Int {
    val diff = abs(a - b)
    return min(diff, modulus - diff)
  }

  const val HISTORY_LIMIT: Int = MAX_HISTORY
}
