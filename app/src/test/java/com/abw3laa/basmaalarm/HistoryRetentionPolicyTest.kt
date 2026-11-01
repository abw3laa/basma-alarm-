package com.abw3laa.basmaalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRetentionPolicyTest {
    data class Event(val id: Int, val time: Long)

    @Test fun keepsNewestEventsInChronologicalOrder() {
        val input = listOf(Event(1, 100), Event(2, 50), Event(3, 300), Event(4, 200))
        val kept = HistoryRetentionPolicy.keepNewest(input, 3) { it.time }
        assertEquals(listOf(1, 4, 3), kept.map { it.id })
        assertTrue(kept.zipWithNext().all { (a, b) -> a.time <= b.time })
    }

    @Test fun enforcesMaximumAndHandlesEmptyOrZeroLimit() {
        val items = (0..700).map { Event(it, it.toLong()) }
        val kept = HistoryRetentionPolicy.keepNewest(items, 500) { it.time }
        assertEquals(500, kept.size)
        assertEquals(201, kept.first().id)
        assertEquals(700, kept.last().id)
        assertTrue(HistoryRetentionPolicy.keepNewest(emptyList<Event>(), 10) { it.time }.isEmpty())
        assertTrue(HistoryRetentionPolicy.keepNewest(items, 0) { it.time }.isEmpty())
    }
}
