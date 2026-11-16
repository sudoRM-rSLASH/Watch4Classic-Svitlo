package com.yehor.lightwatch

import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TileRingTest {
    private val flags = BooleanArray(48) { it in 37..42 }
    private val maybe = BooleanArray(48) { it == 43 }

    private fun size(e: LayoutElement) = e.toLayoutElementProto().serializedSize

    private fun layout(): Box = Box.Builder()
        .addContent(TileRing.dashes(flags, maybe, curSlot = 40, yellowAsOutage = true))
        .addContent(TileRing.hours(hot = 20))
        .addContent(TileRing.nowMark(LocalDate.of(2026, 11, 14), 20 * 60 + 6))
        .build()

    @Test fun ringLayoutStaysSmall() {
        // the tile ships one layout per half hour (12 of them) and a tile is limited to a binder transaction;
        // measured at about 5 KB
        val bytes = size(layout())
        assertTrue("one ring layout is $bytes bytes", bytes < 9_000)
    }

    @Test fun buildsWithoutDataToo() {
        val bytes = size(TileRing.dashes(null, null, curSlot = -1, yellowAsOutage = false))
        assertTrue(bytes > 0)
    }
}
