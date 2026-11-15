package com.yehor.lightwatch

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.DegreesProp
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcAdapter
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.ArcSpacer
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import java.time.LocalDate
import kotlin.math.max

/**
 * The day around the tile, built from ProtoLayout arcs. Two layouts, like the day dial in the app:
 *
 * - **one ring, 24 h**: the hours 00-23 on the outer edge, inside them 48 dashes (one per half hour, 00:00 at
 *   the top);
 * - **two rings, 12 h each**: the outer ring is 00:00-12:00 and the inner one 12:00-24:00, 24 dashes each, so
 *   a dash is twice as long, and every hour is labelled (00-11 outside, 12-23 inside); like reading a clock.
 *
 * Both have a white mark at the current time. See [DialModel] for the colours. It needs no Context.
 */
object TileRing {
    private const val THICKNESS_DP = 8f

    /** The radius of the tile in dp that the dash geometry is computed for (a 234 dp round screen). */
    private const val TILE_RADIUS_DP = 117f

    // one ring: a round cap adds half the thickness at both ends of a line (about 2 degrees each on this ring), so
    // the line itself is short: 1.6 + 2 * 2 is a dash of about 5.6 degrees, 48 * (1.6 + 5.9) = 360
    private const val DASH_DEG = 1.6f
    private const val GAP_DEG = 5.9f

    /** The band on the outer edge for the hour labels (9 sp is the smallest text Google recommends on a tile). */
    const val LABEL_BAND_DP = 13f
    private const val RING_GAP_DP = 3f

    /**
     * How far the middle of the tile has to be kept free of text: the rings and the labels need this. With two
     * rings and no labels inside the inner one there is more room than with them.
     */
    fun centerPaddingDp(rings: Int, innerLabels: Boolean = true): Float = when {
        rings != 2 -> 28f
        innerLabels -> 50f
        else -> 38f
    }

    /** The size of the countdown in the middle: the free space is smaller with two rings. */
    fun countdownSp(rings: Int, innerLabels: Boolean = true): Float = when {
        rings != 2 -> 30f
        innerLabels -> 26f
        else -> 28f
    }

    /**
     * The line and the gap, in degrees, of a dash on a ring whose middle has the radius [radiusDp]: the dash
     * looks [visibleDeg] long including the two round caps, and a dash and its gap are [pitchDeg] together.
     */
    fun dashGeometry(radiusDp: Float, pitchDeg: Float, visibleDeg: Float): Pair<Float, Float> {
        val capDeg = Math.toDegrees((THICKNESS_DP / 2 / radiusDp).toDouble()).toFloat()
        val line = max(0.5f, visibleDeg - 2 * capDeg)
        return line to (pitchDeg - line)
    }

    /** One ring: the slots it shows, how far its dashes are from the edge, and the hours it is labelled with. */
    private class Ring(
        val firstSlot: Int, val slots: Int, val dashInset: Float,
        val firstHour: Int, val labelInset: Float, val labelSp: Float,
    ) {
        val pitchDeg get() = 360f / slots
        val hours get() = slots / 2
        val hourDeg get() = 360f / hours
    }

    private fun rings(count: Int): List<Ring> =
        if (count == 2) {
            val inner = LABEL_BAND_DP + THICKNESS_DP + RING_GAP_DP
            listOf(
                Ring(0, 24, LABEL_BAND_DP, 0, 0f, 10f),
                Ring(24, 24, inner, 12, inner + THICKNESS_DP + 1f, 10f),
            )
        } else {
            listOf(Ring(0, 48, LABEL_BAND_DP, 0, 0f, 9f))
        }

    private fun color(role: DialModel.Role, past: Boolean): Int = when (role) {
        DialModel.Role.RED -> if (past) Palette.RED_DIM else Palette.RED
        DialModel.Role.AMBER -> if (past) Palette.AMBER_DIM else Palette.AMBER
        DialModel.Role.GREEN -> if (past) Palette.GREEN_DIM else Palette.GREEN
        DialModel.Role.NO_DATA -> Palette.NODATA
    }

    /** A box as large as the tile; [inset] keeps its content that far from the edge. */
    private fun fullBox(inset: Float = 0f, vararg content: LayoutElement): Box {
        val b = Box.Builder().setWidth(expand()).setHeight(expand())
        if (inset > 0f) {
            b.setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(inset)).build())
                    .build()
            )
        }
        content.forEach { b.addContent(it) }
        return b.build()
    }

    /**
     * The layers of the ring, to be stacked on the tile under its centre: the dashes, the hours and the mark of
     * the current time. [rings] is 1 or 2; [flags] and [maybe] are the slots of [date], [curSlot] the current
     * one (slots before it are dark), [yellowAsOutage] makes the yellow slots amber instead of green.
     * With two rings [innerLabels] says whether the hours 12-24 are labelled inside the inner ring as well.
     */
    fun layers(
        rings: Int, flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, yellowAsOutage: Boolean,
        date: LocalDate, nowMinute: Int, hotHour: Int, innerLabels: Boolean = true,
    ): List<LayoutElement> {
        val list = rings(rings)
        val out = ArrayList<LayoutElement>()
        for (ring in list) out += dashes(ring, flags, maybe, curSlot, yellowAsOutage)
        out += hours(if (innerLabels) list else list.take(1), hotHour)
        out += nowMark(list, date, nowMinute)
        return out
    }

    private fun dashes(
        ring: Ring, flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, yellowAsOutage: Boolean,
    ): LayoutElement {
        val (line, gap) =
            if (ring.slots == 48) DASH_DEG to GAP_DEG
            else dashGeometry(TILE_RADIUS_DP - ring.dashInset - THICKNESS_DP / 2, ring.pitchDeg, ring.pitchDeg - 3.5f)
        val arc = Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        for (k in 0 until ring.slots) {
            val i = ring.firstSlot + k
            val role = DialModel.roleOf(flags, maybe, i, yellowAsOutage)
            arc.addContent(
                ArcLine.Builder()
                    .setLength(degrees(line))
                    .setThickness(dp(THICKNESS_DP))
                    .setColor(argb(color(role, DialModel.isPast(i, curSlot))))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build()
            )
            arc.addContent(ArcSpacer.Builder().setLength(degrees(gap)).build())
        }
        return fullBox(ring.dashInset, arc.build())
    }

    /** Every hour of a ring on its edge, each label upright; the hour [hot] (the current one) is white and bold. */
    private fun hours(rings: List<Ring>, hot: Int): LayoutElement {
        val layers = rings.map { ring ->
            val box = Box.Builder().setWidth(expand()).setHeight(expand())
            if (ring.labelInset > 0f) {
                box.setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(ring.labelInset)).build())
                        .build()
                )
            }
            for (n in 0 until ring.hours) {
                val h = ring.firstHour + n
                val style = FontStyle.Builder()
                    .setSize(sp(ring.labelSp))
                    .setColor(argb(if (h == hot) Palette.WHITE else Palette.LABEL))
                if (h == hot) style.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                box.addContent(
                    Arc.Builder()
                        .setAnchorAngle(degrees(n * ring.hourDeg))
                        .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                        .addContent(
                            ArcAdapter.Builder()
                                .setRotateContents(false)
                                .setContent(Text.Builder().setText("%02d".format(h)).setFontStyle(style.build()).build())
                                .build()
                        )
                        .build()
                )
            }
            box.build()
        }
        return fullBox(0f, *layers.toTypedArray())
    }

    /**
     * The current time as a white stroke across the ring that it is on. Its angle is computed by the watch from
     * the platform time (so it keeps moving between layouts); [nowMinute] is only the value used by renderers
     * that cannot.
     */
    private fun nowMark(rings: List<Ring>, date: LocalDate, nowMinute: Int): LayoutElement {
        val ring = rings.last { nowMinute >= it.firstSlot * 30 }
        val periodSeconds = ring.slots * 1800f // what one turn of this ring covers
        val offsetSeconds = ring.firstSlot * 1800f
        val startOfDay = date.atStartOfDay(Schedule.ZONE).toInstant()
        val seconds = DynamicInstant.withSecondsPrecision(startOfDay)
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
            .toIntSeconds()
        val staticAngle = (nowMinute * 60f - offsetSeconds) / periodSeconds * 360f
        val angle = DegreesProp.Builder(staticAngle)
            .setDynamicValue(seconds.asFloat().plus(-offsetSeconds).times(360f / periodSeconds))
            .build()
        // on one ring the stroke reaches 6 dp inward, on two rings it stays on its own ring
        val extra = if (rings.size == 1) 6f else 3f
        return fullBox(
            ring.dashInset - if (rings.size == 1) 0f else extra / 2,
            Arc.Builder()
                .setAnchorAngle(angle)
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                .addContent(
                    ArcLine.Builder()
                        .setLength(degrees(1.2f))
                        .setThickness(dp(THICKNESS_DP + extra))
                        .setColor(argb(Palette.WHITE))
                        .setStrokeCap(LayoutElementBuilders.STROKE_CAP_BUTT)
                        .build()
                )
                .build()
        )
    }
}
