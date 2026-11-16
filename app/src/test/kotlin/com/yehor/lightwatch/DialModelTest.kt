package com.yehor.lightwatch

import com.yehor.lightwatch.DialModel.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialModelTest {
    // slot 0 on, 1 off, 2 off, 3 yellow, 4 on
    private val flags = booleanArrayOf(false, true, true, false, false)
    private val maybe = booleanArrayOf(false, false, false, true, false)

    @Test fun powerAndOutageRoles() {
        assertEquals(Role.GREEN, DialModel.roleOf(flags, maybe, 0, yellowAsOutage = true))
        assertEquals(Role.RED, DialModel.roleOf(flags, maybe, 1, yellowAsOutage = true))
        assertEquals(Role.GREEN, DialModel.roleOf(flags, maybe, 4, yellowAsOutage = false))
    }

    @Test fun yellowSlotFollowsTheSetting() {
        assertEquals(Role.AMBER, DialModel.roleOf(flags, maybe, 3, yellowAsOutage = true))
        assertEquals(Role.GREEN, DialModel.roleOf(flags, maybe, 3, yellowAsOutage = false))
    }

    @Test fun yellowWinsEvenWhenTheFlagCountsItAsNoPower() {
        // Store.load marks a yellow slot as "no power" when the setting is on; it must still be amber, not red
        val asOutage = booleanArrayOf(false, true, true, true, false)
        assertEquals(Role.AMBER, DialModel.roleOf(asOutage, maybe, 3, yellowAsOutage = true))
    }

    @Test fun noData() {
        assertEquals(Role.NO_DATA, DialModel.roleOf(null, null, 0, yellowAsOutage = true))
    }

    @Test fun pastSlotsAreOnlyTheOnesBeforeTheCurrent() {
        assertTrue(DialModel.isPast(3, curSlot = 4))
        assertFalse(DialModel.isPast(4, curSlot = 4)) // the current slot is not over yet
        assertFalse(DialModel.isPast(10, curSlot = 4))
        assertFalse(DialModel.isPast(0, curSlot = -1)) // another day: nothing is over
    }

    @Test fun angles() {
        assertEquals(0f, DialModel.angleOfMinute(0), 0.001f)
        assertEquals(90f, DialModel.angleOfMinute(6 * 60), 0.001f)
        assertEquals(180f, DialModel.angleOfMinute(12 * 60), 0.001f)
        assertEquals(301.5f, DialModel.angleOfMinute(20 * 60 + 6), 0.001f)
        assertEquals(360f, DialModel.angleOfMinute(1440), 0.001f)
    }

    @Test fun hourOfMinute() {
        assertEquals(20, DialModel.hourOf(20 * 60 + 59))
        assertEquals(23, DialModel.hourOf(1439))
        assertEquals(0, DialModel.hourOf(0))
    }

    @Test fun twoRingsSplitTheDayAtNoon() {
        assertEquals(0, DialModel.ringOfSlot(23, 2))
        assertEquals(1, DialModel.ringOfSlot(24, 2))
        assertEquals(0, DialModel.ringOfSlot(47, 1))
        assertEquals(0f, DialModel.slotAngle(24, 2), 0.001f)
        assertEquals(15f, DialModel.slotAngle(25, 2), 0.001f)
        assertEquals(7.5f, DialModel.slotAngle(1, 1), 0.001f)
        assertEquals(15f, DialModel.slotDegrees(2), 0.001f)
    }

    @Test fun nowMarkGoesAroundTwiceADay() {
        assertEquals(180f, DialModel.angleOfMinute(6 * 60, 2), 0.001f)
        assertEquals(180f, DialModel.angleOfMinute(18 * 60, 2), 0.001f)
        assertEquals(270f, DialModel.angleOfMinute(18 * 60, 1), 0.001f)
        assertEquals(0, DialModel.ringOfMinute(11 * 60 + 59, 2))
        assertEquals(1, DialModel.ringOfMinute(12 * 60, 2))
        assertEquals(0, DialModel.ringOfMinute(20 * 60, 1))
    }

    @Test fun highlightFallsBackToTheOuterLabel() {
        assertEquals(8, DialModel.hotLabel(20, 2, innerLabels = false))
        assertEquals(20, DialModel.hotLabel(20, 2, innerLabels = true))
        assertEquals(20, DialModel.hotLabel(20, 1, innerLabels = false))
    }
}
