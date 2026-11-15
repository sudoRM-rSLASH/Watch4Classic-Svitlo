package com.yehor.lightwatch

import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TileRingTest {
    private val date = LocalDate.of(2026, 11, 14)
    private val flags = BooleanArray(48) { it in 37..42 }
    private val maybe = BooleanArray(48) { it == 43 }

    private fun size(e: LayoutElement) = e.toLayoutElementProto().serializedSize

    private fun layout(rings: Int, minute: Int = 20 * 60 + 6): Box {
        val b = Box.Builder()
        TileRing.layers(
            rings, flags, maybe, curSlot = minute / 30, yellowAsOutage = true,
            date = date, nowMinute = minute, hotHour = minute / 60
        ).forEach { b.addContent(it) }
        return b.build()
    }

    @Test fun oneRingLayoutStaysSmall() {
        // the tile ships one layout per half hour (12 of them) and a tile is limited to a binder transaction
        val bytes = size(layout(1))
        assertTrue("one ring layout is $bytes bytes", bytes < 9_000)
    }

    @Test fun twoRingsLayoutStaysSmall() {
        val bytes = size(layout(2))
        assertTrue("two rings layout is $bytes bytes", bytes < 14_000)
    }

    @Test fun buildsWithoutDataAndOnBothRings() {
        for (rings in 1..2) {
            for (minute in listOf(0, 11 * 60 + 59, 12 * 60, 23 * 60 + 59)) {
                val b = Box.Builder()
                TileRing.layers(rings, null, null, -1, false, date, minute, minute / 60).forEach { b.addContent(it) }
                assertTrue(size(b.build()) > 0)
            }
        }
    }

    @Test fun dashGeometryFillsThePitch() {
        for (radius in listOf(89f, 100f, 117f)) {
            val (line, gap) = TileRing.dashGeometry(radius, 15f, 11.5f)
            assertEquals(15f, line + gap, 0.001f)
            assertTrue("line $line at $radius", line >= 0.5f)
        }
    }

    @Test fun dashOnAnInnerRingIsNotLongerThanItsPitch() {
        val (line, gap) = TileRing.dashGeometry(89f, 15f, 11.5f)
        assertTrue(gap > 3f) // a visible gap remains between two dashes
        assertTrue(line < 15f)
    }

    @Test fun labelsOnlyOutsideMakeTheTileSmallerAndTheCentreRoomier() {
        val b = Box.Builder()
        TileRing.layers(
            2, flags, maybe, curSlot = 40, yellowAsOutage = true, date = date, nowMinute = 20 * 60 + 6,
            hotHour = 20, innerLabels = false
        ).forEach { b.addContent(it) }
        assertTrue(size(b.build()) < size(layout(2)))
        assertTrue(TileRing.centerPaddingDp(2, innerLabels = false) < TileRing.centerPaddingDp(2, innerLabels = true))
        assertTrue(TileRing.countdownSp(2, innerLabels = false) > TileRing.countdownSp(2, innerLabels = true))
    }

    @Test fun centreNeedsMoreRoomWithTwoRings() {
        assertTrue(TileRing.centerPaddingDp(2) > TileRing.centerPaddingDp(1))
        assertTrue(TileRing.countdownSp(2) < TileRing.countdownSp(1))
    }
}
