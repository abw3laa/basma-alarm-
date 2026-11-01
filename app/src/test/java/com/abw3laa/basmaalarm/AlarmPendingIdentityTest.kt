package com.abw3laa.basmaalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPendingIdentityTest {
    @Test fun snoozeIdentityDoesNotDependOnTheDayItWasCreated() {
        val appointment = 20_000L
        assertEquals(appointment, AlarmPendingIdentity.uriEpochDay(appointment, appointment, true))
        assertEquals(appointment, AlarmPendingIdentity.uriEpochDay(appointment, appointment + 1, true))
        assertEquals(appointment + 2, AlarmPendingIdentity.uriEpochDay(appointment, appointment + 2, false))
        assertNotEquals(
            AlarmPendingIdentity.uriEpochDay(appointment, appointment + 1, true),
            AlarmPendingIdentity.uriEpochDay(appointment + 1, appointment + 1, true),
        )
    }

    @Test fun legacyIdentityCleanupCoversMidnightAndSnoozeWindowDays() {
        val base = 20_000L
        val possible = AlarmPendingIdentity.legacySnoozeEpochDays(base)
        assertTrue(base in possible)
        assertTrue(base + 1 in possible)
        assertTrue(base + 2 in possible)
        assertTrue(base + 3 in possible)
    }
}
