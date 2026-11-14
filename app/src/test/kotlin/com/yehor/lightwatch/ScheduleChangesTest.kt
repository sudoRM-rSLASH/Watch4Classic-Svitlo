package com.yehor.lightwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleChangesTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val tomorrow = today.plusDays(1)

    /** "0" on, "1" off, "2" yellow; shorter strings are padded with "0" to the 48 slots of a day. */
    private fun day(s: String) = s.padEnd(Schedule.SLOTS, '0').map { it.digitToInt() }.toIntArray()

    private fun detect(
        old: Map<LocalDate, IntArray>, fresh: Map<LocalDate, IntArray>,
        fromSlot: Int = 0, maybeIsOff: Boolean = true,
    ) = ScheduleChanges.detect(old, fresh, today, fromSlot, maybeIsOff)

    @Test fun emptyCache_reportsNothing() {
        val r = detect(emptyMap(), mapOf(today to day("1"), tomorrow to day("11")))
        assertNull(r.today)
        assertNull(r.tomorrow)
    }

    @Test fun unchangedSchedule_reportsNothing() {
        val d = mapOf(today to day("0011"), tomorrow to day("11"))
        val r = detect(d, d.mapValues { it.value.copyOf() })
        assertNull(r.today)
        assertNull(r.tomorrow)
    }

    @Test fun todayChanged_reportsAddedAndRemovedRanges() {
        // outage 02:00-03:00 (slots 4-5) moves to 04:00-05:30 (slots 8-10)
        val old = mapOf(today to day("0000110"))
        val fresh = mapOf(today to day("000000001110"))
        val diff = detect(old, fresh).today!!
        assertEquals(listOf(8 to 11), diff.added)
        assertEquals(listOf(4 to 6), diff.removed)
    }

    @Test fun changeInThePast_isIgnored() {
        val old = mapOf(today to day("1100"))
        val fresh = mapOf(today to day("0000"))
        // it is 10:00 (slot 20): the 00:00-01:00 outage is long over
        assertNull(detect(old, fresh, fromSlot = 20).today)
        // at 00:30 it still counts
        assertNotNull(detect(old, fresh, fromSlot = 1).today)
    }

    @Test fun yellowOnlyChange_isReportedOnlyWhenItIsVisible() {
        val old = mapOf(today to day("0000110000"))
        val fresh = mapOf(today to day("0000112000"))
        // yellow counts as no power: the outage got half an hour longer
        assertEquals(listOf(6 to 7), detect(old, fresh, maybeIsOff = true).today!!.added)
        // yellow counts as power on: the user sees no difference, so no notification
        assertNull(detect(old, fresh, maybeIsOff = false).today)
    }

    @Test fun tomorrowAppeared_reportsItsFlags() {
        val r = detect(mapOf(today to day("11")), mapOf(today to day("11"), tomorrow to day("0011")))
        val flags = r.tomorrow!!
        assertTrue(flags[2] && flags[3] && !flags[0] && !flags[4])
    }

    @Test fun tomorrowAlreadyCached_isNotReportedAgain() {
        val old = mapOf(today to day("11"), tomorrow to day("0011"))
        val fresh = mapOf(today to day("11"), tomorrow to day("1100")) // changed, but not "appeared"
        assertNull(detect(old, fresh).tomorrow)
    }

    @Test fun diff_ofIdenticalDays_isNull() {
        assertNull(ScheduleChanges.diff(BooleanArray(48) { it < 4 }, BooleanArray(48) { it < 4 }, 0))
    }
}
