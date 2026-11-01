package com.abw3laa.basmaalarm

/** Stable PendingIntent identity helpers for scheduled and snoozed alarm occurrences. */
internal object AlarmPendingIdentity {
    fun uriEpochDay(baseEpochDay: Long, scheduledEpochDay: Long, snooze: Boolean): Long =
        if (snooze) baseEpochDay else scheduledEpochDay

    fun legacySnoozeEpochDays(baseEpochDay: Long): LongRange = (baseEpochDay - 3)..(baseEpochDay + 3)
}
