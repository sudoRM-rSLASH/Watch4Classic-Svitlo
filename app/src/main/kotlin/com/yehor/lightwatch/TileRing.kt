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

/**
 * The 24-hour ring around the tile, built from ProtoLayout arcs, laid out like the day dial in the app: the 24
 * hours on the outer edge, inside them 48 dashes (one per half hour, 00:00 at the top) and a white mark at the
 * current time. See [DialModel] for the colours. It needs no Context.
 */
object TileRing {
    private const val THICKNESS_DP = 8f
    // a round cap adds half the thickness at both ends of a line (about 2 degrees each on this ring), so the
    // line itself is short: 1.6 + 2 * 2 is a dash of about 5.6 degrees, 48 * (1.6 + 5.9) = 360
    private const val DASH_DEG = 1.6f
    private const val GAP_DEG = 5.9f

    /** The band on the outer edge for the hour labels (9 sp is the smallest text Google recommends on a tile). */
    const val LABEL_BAND_DP = 13f

    private const val LABEL_SP = 9f

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
     * Today's 48 dashes. [flags] and [maybe] are the slots of the day, [curSlot] the current one (slots before
     * it are dark), [yellowAsOutage] makes the yellow slots amber instead of green.
     */
    fun dashes(flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, yellowAsOutage: Boolean): LayoutElement {
        val arc = Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        for (i in 0 until Schedule.SLOTS) {
            val role = DialModel.roleOf(flags, maybe, i, yellowAsOutage)
            arc.addContent(
                ArcLine.Builder()
                    .setLength(degrees(DASH_DEG))
                    .setThickness(dp(THICKNESS_DP))
                    .setColor(argb(color(role, DialModel.isPast(i, curSlot))))
                    .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                    .build()
            )
            arc.addContent(ArcSpacer.Builder().setLength(degrees(GAP_DEG)).build())
        }
        return fullBox(LABEL_BAND_DP, arc.build())
    }

    /** All 24 hours on the outer edge, each label upright; the hour [hot] (the current one) is white and bold. */
    fun hours(hot: Int): LayoutElement {
        val box = Box.Builder().setWidth(expand()).setHeight(expand())
        for (h in 0 until 24) {
            val style = FontStyle.Builder()
                .setSize(sp(LABEL_SP))
                .setColor(argb(if (h == hot) Palette.WHITE else Palette.LABEL))
            if (h == hot) style.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
            box.addContent(
                Arc.Builder()
                    .setAnchorAngle(degrees(h * 15f))
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
        return box.build()
    }

    /**
     * The current time as a white stroke across the ring. Its angle is computed by the watch from the platform
     * time (so it keeps moving between layouts); [nowMinute] is only the value used by renderers that cannot.
     */
    fun nowMark(date: LocalDate, nowMinute: Int): LayoutElement {
        val startOfDay = date.atStartOfDay(Schedule.ZONE).toInstant()
        val seconds = DynamicInstant.withSecondsPrecision(startOfDay)
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
            .toIntSeconds()
        val angle = DegreesProp.Builder(DialModel.angleOfMinute(nowMinute))
            .setDynamicValue(seconds.asFloat().times(360f / 86_400f))
            .build()
        return fullBox(
            LABEL_BAND_DP,
            Arc.Builder()
                .setAnchorAngle(angle)
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                .addContent(
                    ArcLine.Builder()
                        .setLength(degrees(1.2f))
                        .setThickness(dp(THICKNESS_DP + 6f))
                        .setColor(argb(Palette.WHITE))
                        .setStrokeCap(LayoutElementBuilders.STROKE_CAP_BUTT)
                        .build()
                )
                .build()
        )
    }
}
