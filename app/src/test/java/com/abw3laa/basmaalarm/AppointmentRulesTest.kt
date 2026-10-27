package com.abw3laa.basmaalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AppointmentRulesTest {
    private val start = LocalDate.of(2026, 1, 1)

    @Test fun nextDateIsAnActualScheduledCycle() {
        val next = AppointmentRules.nextOccurrence("s1", "Main", start, 28, 2, emptySet(), LocalDate.of(2026, 3, 1))
        assertEquals(LocalDate.of(2026, 3, 26), next?.baseDate)
        assertEquals(2, next?.ordinal)
    }

    @Test fun futureAppointmentCannotBeConfirmed() {
        val occurrence = AppointmentOccurrence("s1", "Main", start, 1)
        assertFalse(AppointmentRules.canConfirm(occurrence, LocalDate.of(2025, 12, 31)))
        assertEquals(AppointmentState.UPCOMING, AppointmentRules.state(occurrence, LocalDate.of(2025, 12, 31), false))
    }

    @Test fun appointmentIsConfirmableThroughoutThreeDayWindowOnly() {
        val occurrence = AppointmentOccurrence("s1", "Main", start, 1)
        assertTrue(AppointmentRules.canConfirm(occurrence, start))
        assertTrue(AppointmentRules.canConfirm(occurrence, start.plusDays(2)))
        assertFalse(AppointmentRules.canConfirm(occurrence, start.plusDays(3)))
        assertEquals(AppointmentState.AVAILABLE, AppointmentRules.state(occurrence, start.plusDays(2), false))
        assertEquals(AppointmentState.MISSED, AppointmentRules.state(occurrence, start.plusDays(3), false))
    }

    @Test fun exhaustedScheduleHasNoSyntheticNextDate() {
        val next = AppointmentRules.nextOccurrence("s1", "Main", start, 28, 1, emptySet(), start.plusDays(3))
        assertNull(next)
    }

    @Test fun completedCyclesAreSkippedAndRemainingCyclesAreKept() {
        val occurrences = AppointmentRules.occurrences("s1", "Main", start, 7, 3, setOf(start.toEpochDay()))
        assertEquals(listOf(start.plusDays(7), start.plusDays(14)), occurrences.map { it.baseDate })
    }

    @Test fun invalidIntervalIsClampedAndNegativeCountIsEmpty() {
        assertEquals(start.plusDays(1), AppointmentRules.occurrences("s1", "Main", start, 0, 2, emptySet())[1].baseDate)
        assertTrue(AppointmentRules.occurrences("s1", "Main", start, 7, -1, emptySet()).isEmpty())
    }

    @Test fun sameDateInDifferentSchedulesKeepsDistinctIdentityAndCompletionState() {
        val day = start.toEpochDay()
        val first = AppointmentRules.occurrences("schedule-a", "A", start, 28, 1, setOf(day))
        val second = AppointmentRules.occurrences("schedule-b", "B", start, 28, 1, emptySet())

        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertNotEquals(first.firstOrNull()?.scheduleId, second.first().scheduleId)
        assertEquals(day, second.single().epochDay)
    }
}
