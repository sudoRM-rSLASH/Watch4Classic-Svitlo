package com.yehor.lightwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test fun sameStartAndEnd_meansOff() {
        assertFalse(QuietHours.enabled(0, 0))
        assertFalse(QuietHours.enabled(7, 7))
        assertTrue((0..23).none { QuietHours.active(5, 5, it) })
    }

    @Test fun windowWithinOneDay() {
        // 13:00-17:00: the end hour is exclusive
        assertEquals(listOf(13, 14, 15, 16), (0..23).filter { QuietHours.active(13, 17, it) })
    }

    @Test fun windowAcrossMidnight() {
        // 23:00-07:00
        val quiet = (0..23).filter { QuietHours.active(23, 7, it) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 23), quiet)
        assertFalse(QuietHours.active(23, 7, 7))
        assertFalse(QuietHours.active(23, 7, 22))
    }

    @Test fun windowFromMidnight() {
        assertEquals((0..7).toList(), (0..23).filter { QuietHours.active(0, 8, it) })
    }

    @Test fun presetsAreRealWindows() {
        assertTrue(QuietHours.PRESETS.all { (f, t) -> QuietHours.enabled(f, t) })
    }
}
