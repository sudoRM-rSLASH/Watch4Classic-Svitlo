package com.yehor.lightwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StoreTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val tomorrow = today.plusDays(1)

    private fun day(state: Int) = IntArray(Schedule.SLOTS) { state }

    @Test fun merge_freshWinsAndOldDaysAreDropped() {
        val old = mapOf(today.minusDays(2) to day(1), today.minusDays(1) to day(1), today to day(1))
        val merged = Store.merge(old, mapOf(today to day(0)), today)
        assertEquals(listOf(today.minusDays(1), today), merged.keys.toList())
        assertEquals(0, merged.getValue(today)[0])
    }

    @Test fun merge_keepsCachedTomorrowWhenItWasNotRequested() {
        val merged = Store.merge(mapOf(tomorrow to day(1)), mapOf(today to day(0)), today)
        assertTrue(tomorrow in merged)
    }

    @Test fun merge_dropsTomorrowThatTheSourceWithdrew() {
        val old = mapOf(today to day(1), tomorrow to day(1))
        val merged = Store.merge(old, mapOf(today to day(0)), today, missing = listOf(tomorrow))
        assertEquals(listOf(today), merged.keys.toList())
    }

    @Test fun merge_freshTomorrowWinsOverMissing() {
        // the same date cannot be both fresh and missing in practice, but fresh data must never be lost
        val merged = Store.merge(emptyMap(), mapOf(tomorrow to day(1)), today, missing = listOf(tomorrow))
        assertTrue(tomorrow in merged)
    }

    @Test fun parseDays_readsValidDaysAndSkipsBrokenOnes() {
        val ok = "0".repeat(24) + "1".repeat(23) + "2" // 48 slots
        val days = Store.parseDays("2026-11-10=$ok;2026-11-11=short;not-a-date=$ok;2026-11-12=${ok.dropLast(1)}x")
        // a non-digit slot is read as "power on", the other three entries are dropped
        assertEquals(setOf(today, today.plusDays(2)), days.keys)
        assertEquals(2, days.getValue(today)[47])
        assertEquals(1, days.getValue(today)[24])
        assertEquals(0, days.getValue(today.plusDays(2))[47])
    }

    @Test fun parseDays_emptyString() = assertTrue(Store.parseDays("").isEmpty())

    @Test fun requested_poeSkipsTomorrowBeforeNoon() {
        assertEquals(listOf(today), Source.POE.requested(today, hour = 11))
        assertEquals(listOf(today, tomorrow), Source.POE.requested(today, hour = 12))
    }

    @Test fun requested_poeSkipsTomorrowWhenRecentlyChecked() {
        assertEquals(listOf(today), Source.POE.requested(today, hour = 18, withTomorrow = false))
        assertEquals(listOf(today, tomorrow), Source.POE.requested(today, hour = 18, withTomorrow = true))
    }

    @Test fun requested_otherSourcesAlwaysAskForTomorrow() {
        assertEquals(listOf(today, tomorrow), Source.BEZSVITLA.requested(today, hour = 3))
        assertEquals(listOf(today, tomorrow), Source.ENERGY_UA.requested(today, hour = 3))
    }
}
