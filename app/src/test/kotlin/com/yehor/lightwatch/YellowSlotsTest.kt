package com.yehor.lightwatch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * What the "yellow slots" setting really changes. A yellow slot (state 2) is the switching time that follows
 * an outage, so the setting moves the END of an outage (and the lengths derived from it), never its start.
 */
class YellowSlotsTest {
    private val day = LocalDate.of(2026, 11, 10)

    // 0 on, 1 off, 2 yellow; queue 3.2 on 2026-11-10: outages 00:00-02:30, 06:30-10:30, 12:30-16:30, 18:30-22:00,
    // each followed by one yellow slot
    private val raw = mapOf(
        day to "111112000000011111111200011111111200011111112000".map { it.digitToInt() }.toIntArray()
    )

    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 10, h, m, 0, 0, Schedule.ZONE)

    private fun status(maybeIsOff: Boolean, now: ZonedDateTime) =
        Schedule.status(Store.flagsOf(raw, maybeIsOff), now)!!

    @Test fun duringAnOutage_theEndMovesByTheYellowSlot() {
        assertEquals(at(11, 0), status(maybeIsOff = true, now = at(8, 0)).changeAt)  // yellow counts as no power
        assertEquals(at(10, 30), status(maybeIsOff = false, now = at(8, 0)).changeAt) // yellow counts as power
    }

    @Test fun whilePowerIsOn_theStartOfTheNextOutageDoesNotMove() {
        assertEquals(at(6, 30), status(maybeIsOff = true, now = at(5, 0)).changeAt)
        assertEquals(at(6, 30), status(maybeIsOff = false, now = at(5, 0)).changeAt)
    }

    @Test fun whilePowerIsOn_theLengthOfTheNextOutageChanges() {
        assertEquals(Duration.ofMinutes(270), status(maybeIsOff = true, now = at(5, 0)).nextLen)  // 06:30-11:00
        assertEquals(Duration.ofMinutes(240), status(maybeIsOff = false, now = at(5, 0)).nextLen) // 06:30-10:30
    }

    @Test fun dayTotalChangesByTheNumberOfYellowSlots() {
        // 28 off slots + 4 yellow slots
        assertEquals(Duration.ofHours(16), Schedule.offDuration(Store.flagsOf(raw, true).getValue(day)))
        assertEquals(Duration.ofHours(14), Schedule.offDuration(Store.flagsOf(raw, false).getValue(day)))
    }
}
