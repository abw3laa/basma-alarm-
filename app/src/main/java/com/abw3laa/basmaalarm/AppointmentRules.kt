package com.abw3laa.basmaalarm

import java.time.LocalDate

data class AppointmentOccurrence(
    val scheduleId: String,
    val scheduleName: String,
    val baseDate: LocalDate,
    val ordinal: Int,
    val completedAtEpochMillis: Long? = null,
    val completionMethod: String? = null,
    val note: String? = null,
) {
    val epochDay: Long get() = baseDate.toEpochDay()
    val windowEnd: LocalDate get() = baseDate.plusDays(2)
}

enum class AppointmentState { UPCOMING, AVAILABLE, COMPLETED, MISSED }

data class AppointmentEvent(
    val scheduleId: String,
    val scheduleName: String,
    val appointmentEpochDay: Long,
    val confirmedAtEpochMillis: Long,
    val method: String,
    val status: AppointmentState = AppointmentState.COMPLETED,
    val note: String = "",
)

/** Pure date rules shared by presentation, scheduling, and JVM unit tests. */
object AppointmentRules {
    fun occurrences(
        scheduleId: String,
        scheduleName: String,
        start: LocalDate,
        intervalDays: Int,
        count: Int,
        completedEpochDays: Set<Long>,
    ): List<AppointmentOccurrence> = (0 until count.coerceAtLeast(0)).map { index ->
        AppointmentOccurrence(
            scheduleId = scheduleId,
            scheduleName = scheduleName,
            baseDate = start.plusDays(index.toLong() * intervalDays.coerceAtLeast(1)),
            ordinal = index + 1,
        )
    }.filterNot { it.epochDay in completedEpochDays }

    /** Returns only an actual uncompleted cycle whose three-day window has not ended. */
    fun nextOccurrence(
        scheduleId: String,
        scheduleName: String,
        start: LocalDate,
        intervalDays: Int,
        count: Int,
        completedEpochDays: Set<Long>,
        today: LocalDate,
    ): AppointmentOccurrence? = occurrences(
        scheduleId, scheduleName, start, intervalDays, count, completedEpochDays
    ).firstOrNull { !it.windowEnd.isBefore(today) }

    fun state(occurrence: AppointmentOccurrence, today: LocalDate, completed: Boolean): AppointmentState = when {
        completed -> AppointmentState.COMPLETED
        today.isBefore(occurrence.baseDate) -> AppointmentState.UPCOMING
        !today.isAfter(occurrence.windowEnd) -> AppointmentState.AVAILABLE
        else -> AppointmentState.MISSED
    }

    fun canConfirm(occurrence: AppointmentOccurrence, today: LocalDate): Boolean =
        !today.isBefore(occurrence.baseDate) && !today.isAfter(occurrence.windowEnd)
}
