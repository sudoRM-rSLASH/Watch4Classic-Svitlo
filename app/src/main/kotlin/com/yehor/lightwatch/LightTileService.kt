package com.yehor.lightwatch

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Tile: a 24-hour ring around the edge (see [TileRing]); queue, status and countdown in the centre.
 * Timeline: a layout per half hour (~3 h ahead); the countdown is dynamic text computed by the watch.
 */
class LightTileService : TileService() {
    private var queue = ""
    private var maybe: Map<LocalDate, BooleanArray> = emptyMap()
    private var stale = false

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Refresh.ifStale(this)

        val days = Store.load(this, Settings.maybeIsOff(this))
        stale = Store.isStale(this)
        maybe = Store.loadMaybe(this)
        val err = Store.error(this)
        val fetched = Store.fetchedAt(this)
        queue = Settings.queue(this)
        val start = ZonedDateTime.now(Schedule.ZONE).truncatedTo(ChronoUnit.MINUTES)
        // Half-hour slot boundaries: within a slot only the countdown changes, and the watch computes it.
        val firstBoundary = start.truncatedTo(ChronoUnit.HOURS).plusMinutes(if (start.minute >= 30) 60 else 30)
        val bounds = listOf(start) + List(TIMELINE_SLOTS) { firstBoundary.plusMinutes(30L * it) }

        val timeline = TimelineBuilders.Timeline.Builder()
        for (i in 0 until bounds.size - 1) {
            val t = bounds[i]
            timeline.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(render(days, t, err, fetched))
                            .build()
                    )
                    .setValidity(
                        TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(t.toInstant().toEpochMilli())
                            .setEndMillis(bounds[i + 1].toInstant().toEpochMilli())
                            .build()
                    )
                    .build()
            )
        }

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(timeline.build())
                .build()
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())

    // ---------------------------------------------------------------- layout

    private fun render(
        days: Map<LocalDate, BooleanArray>,
        now: ZonedDateTime,
        err: String?,
        fetched: Long,
    ): LayoutElement {
        val st = Schedule.status(days, now)
        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(ring(days, now))
            .addContent(TileRing.hours(DialModel.hourOf(now.hour * 60 + now.minute)))
            .addContent(TileRing.nowMark(now.toLocalDate(), now.hour * 60 + now.minute))
            .addContent(center(st, now, err, fetched))
            .build()
    }

    /** Today's 48 dashes: slots that are over are dark, yellow slots follow the "yellow slots" setting. */
    private fun ring(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime): LayoutElement {
        val date = now.toLocalDate()
        return TileRing.dashes(days[date], maybe[date], Schedule.slotOf(now), Settings.maybeIsOff(this))
    }

    private fun center(
        st: Schedule.Status?,
        now: ZonedDateTime,
        err: String?,
        fetched: Long,
    ): LayoutElement {
        val col = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(getString(R.string.queue_upper, queue), 11f, LABEL, bold = true))
            .addContent(gap(3f))

        if (st == null) {
            col.addContent(text(getString(R.string.no_data_upper), 13f, GREY, bold = true))
                .addContent(gap(4f))
                .addContent(
                    text(
                        getString(if (err != null) R.string.tile_update_failed else R.string.tile_updating),
                        12f, LABEL
                    )
                )
                .addContent(gap(2f))
                .addContent(text(getString(R.string.tile_tap_to_open), 11f, LABEL))
        } else {
            val switching = Schedule.at(maybe, now) == true
            val color = when { switching -> AMBER; st.off -> RED; else -> GREEN }
            val title = getString(
                when { switching -> R.string.state_switching_upper; st.off -> R.string.state_off_upper; else -> R.string.state_on_upper }
            )
            col.addContent(text(title, 13f, color, bold = true))
                .addContent(gap(2f))
                .addContent(
                    countdown(st, now)
                )
                .addContent(gap(2f))
                .addContent(
                    text(
                        getString(
                            when {
                                st.changeIsMinimum -> R.string.until_no_change
                                st.off -> R.string.power_on_at
                                else -> R.string.power_off_at
                            },
                            hhmm(st.changeAt)
                        ),
                        13f, GREY
                    )
                )
        }

        if (fetched > 0) {
            val t = java.time.Instant.ofEpochMilli(fetched).atZone(Schedule.ZONE)
            col.addContent(gap(3f))
                .addContent(
                    if (err != null || stale) {
                        val from = if (t.toLocalDate() == now.toLocalDate()) hhmm(t) else t.format(DDHHMM)
                        text(getString(R.string.tile_data_from, from), 10f, AMBER)
                    } else text(getString(R.string.tile_updated, hhmm(t)), 10f, LABEL)
                )
        }
        return Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(CENTER_PADDING_DP)).build())
                    .build()
            )
            .addContent(col.build())
            .build()
    }

    /**
     * Large "2h 17m" / "5h" / "34m" countdown (localized units) as dynamic text (minutes rounded up, +59 s).
     * Older renderers without support show the static value as of the layout start.
     */
    private fun countdown(st: Schedule.Status, now: ZonedDateTime): LayoutElement {
        val pre = if (st.changeIsMinimum) "≥ " else ""
        val dur = DynamicInstant.platformTimeWithSecondsPrecision()
            .durationUntil(DynamicInstant.withSecondsPrecision(st.changeAt.toInstant().plusSeconds(59)))
        val h = dur.toIntHours()
        val m = dur.minutesPart
        fun c(s: String) = DynamicString.constant(s)
        val unitH = getString(R.string.unit_h)
        val unitM = getString(R.string.unit_m)
        val dyn = c(pre).concat(
            DynamicString.onCondition(h.gt(0))
                .use(
                    DynamicString.onCondition(m.eq(0))
                        .use(h.format().concat(c(unitH)))
                        .elseUse(h.format().concat(c("$unitH ")).concat(m.format()).concat(c(unitM)))
                )
                .elseUse(m.format().concat(c(unitM)))
        )
        val fallback = pre + Fmt.duration(this, st.remaining(now).plusSeconds(59))
        return Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(fallback).setDynamicValue(dyn).build())
            .setLayoutConstraintsForDynamicText(
                TypeBuilders.StringLayoutConstraint.Builder("≥ " + getString(R.string.duration_hm, 23, 59))
                    .setAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .build()
            )
            .setMaxLines(1)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(30f))
                    .setColor(argb(WHITE))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .build()
            )
            .build()
    }

    private fun hhmm(t: ZonedDateTime): String =
        if (t.hour == 0 && t.minute == 0 && t.toLocalDate() != ZonedDateTime.now(Schedule.ZONE).toLocalDate())
            "24:00" else t.format(HHMM)

    private fun gap(h: Float): LayoutElement = Spacer.Builder().setHeight(dp(h)).build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): LayoutElement =
        Text.Builder()
            .setText(s)
            .setMaxLines(1)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .apply { if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD) }
                    .build()
            )
            .build()

    private companion object {
        const val RES_VERSION = "4"
        /** Keeps the text inside the ring (the hour labels are outside of it). */
        const val CENTER_PADDING_DP = 28f
        /**
         * Half-hour layouts ahead (~6 h). A layout per slot is needed because the strip shifts every 30 min,
         * so a longer timeline means fewer tile rebuilds; real changes of the data call requestUpdate().
         */
        const val TIMELINE_SLOTS = 12
        /** The system re-requests the tile at most this often (it only has to outlive the data refresh job). */
        const val FRESHNESS_MS = 60 * 60_000L
        val DDHHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        const val GREEN = Palette.GREEN
        const val GREEN_DIM = Palette.GREEN_DIM
        const val RED = Palette.RED
        const val RED_DIM = Palette.RED_DIM
        const val NODATA = Palette.NODATA
        const val NODATA_DIM = Palette.NODATA_DIM
        const val GREY = Palette.GREY
        const val LABEL = Palette.LABEL
        const val AMBER = Palette.AMBER
        const val WHITE = Palette.WHITE
    }
}

object Palette {
    const val GREEN = 0xFF4ADE80.toInt()
    const val GREEN_DIM = 0xFF1C4A2C.toInt()
    const val RED = 0xFFFF5A5F.toInt()
    const val RED_DIM = 0xFF5A2224.toInt()
    const val NODATA = 0xFF3A3F47.toInt()
    const val NODATA_DIM = 0xFF22262B.toInt()
    const val GREY = 0xFFC9CDD3.toInt()
    const val LABEL = 0xFF7D838C.toInt()
    const val AMBER = 0xFFFFC14D.toInt()
    const val AMBER_DIM = 0xFF5A4520.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BG = 0xFF000000.toInt()
    const val CARD = 0xFF1C1F24.toInt()
}
