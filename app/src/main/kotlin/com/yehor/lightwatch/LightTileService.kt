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
 * Tile: queue, status and countdown in the centre, a strip of the next 12 hours.
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
                .setFreshnessIntervalMillis(15 * 60_000L)
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
            .addContent(center(st, days, now, err, fetched))
            .build()
    }

    /** Strip of the next 12 hours (24 slots of 30 min). */
    private fun strip(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime): LayoutElement {
        val row = Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        val m = Schedule.upcoming(maybe, now, STRIP_SLOTS)
        Schedule.upcoming(days, now, STRIP_SLOTS).forEachIndexed { i, off ->
            if (i > 0) row.addContent(Spacer.Builder().setWidth(dp(1f)).build())
            val color = when {
                m[i] == true -> AMBER
                off == null -> NODATA
                off -> RED
                else -> GREEN
            }
            row.addContent(
                Box.Builder()
                    .setWidth(dp(5f))
                    .setHeight(dp(if (i == 0) 13f else 9f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(color))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(1.5f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        }
        val labels = Row.Builder()
            .addContent(text("зараз", 9f, LABEL))
            .addContent(Spacer.Builder().setWidth(dp(80f)).build())
            .addContent(text("+12г", 9f, LABEL))
        return Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(row.build())
            .addContent(gap(2f))
            .addContent(labels.build())
            .build()
    }

    private fun center(
        st: Schedule.Status?,
        days: Map<LocalDate, BooleanArray>,
        now: ZonedDateTime,
        err: String?,
        fetched: Long,
    ): LayoutElement {
        val col = Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("ЧЕРГА $queue", 12f, LABEL, bold = true))
            .addContent(gap(6f))

        if (st == null) {
            col.addContent(text("НЕМАЄ ДАНИХ", 13f, GREY, bold = true))
                .addContent(gap(4f))
                .addContent(text(if (err != null) "не вдалось оновити" else "оновлюю…", 12f, LABEL))
                .addContent(gap(2f))
                .addContent(text("торкнись, щоб відкрити", 11f, LABEL))
        } else {
            val switching = Schedule.at(maybe, now) == true
            val color = when { switching -> AMBER; st.off -> RED; else -> GREEN }
            val title = when { switching -> "ПЕРЕМИКАННЯ"; st.off -> "СВІТЛА НЕМАЄ"; else -> "СВІТЛО Є" }
            col.addContent(text(title, 15f, color, bold = true))
                .addContent(gap(2f))
                .addContent(
                    countdown(st, now)
                )
                .addContent(gap(2f))
                .addContent(
                    text(
                        when {
                            st.changeIsMinimum -> "без змін до ${hhmm(st.changeAt)}"
                            st.off -> "увімкнуть о ${hhmm(st.changeAt)}"
                            else -> "вимкнуть о ${hhmm(st.changeAt)}"
                        },
                        15f, GREY
                    )
                )
            st.nextLen?.let {
                val what = if (st.off) "зі світлом" else "без світла"
                col.addContent(gap(2f))
                    .addContent(text("далі ${if (st.nextEndIsMinimum) "≥ " else ""}${Schedule.fmt(it)} $what", 13f, LABEL))
            }
            col.addContent(gap(8f)).addContent(strip(days, now))
        }

        if (fetched > 0) {
            val t = java.time.Instant.ofEpochMilli(fetched).atZone(Schedule.ZONE)
            col.addContent(gap(6f))
                .addContent(
                    if (err != null || stale) text("⚠ дані від ${if (t.toLocalDate() == now.toLocalDate()) hhmm(t) else t.format(DDHHMM)}", 12f, AMBER)
                    else text("оновлено ${hhmm(t)}", 12f, LABEL)
                )
        }
        return Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col.build())
            .build()
    }

    /**
     * Large "2г 17хв" / "5г" / "34хв" countdown as dynamic text (minutes rounded up, +59 s).
     * Older renderers without support show the static value as of the layout start.
     */
    private fun countdown(st: Schedule.Status, now: ZonedDateTime): LayoutElement {
        val pre = if (st.changeIsMinimum) "≥ " else ""
        val dur = DynamicInstant.platformTimeWithSecondsPrecision()
            .durationUntil(DynamicInstant.withSecondsPrecision(st.changeAt.toInstant().plusSeconds(59)))
        val h = dur.toIntHours()
        val m = dur.minutesPart
        fun c(s: String) = DynamicString.constant(s)
        val dyn = c(pre).concat(
            DynamicString.onCondition(h.gt(0))
                .use(
                    DynamicString.onCondition(m.eq(0))
                        .use(h.format().concat(c("г")))
                        .elseUse(h.format().concat(c("г ")).concat(m.format()).concat(c("хв")))
                )
                .elseUse(m.format().concat(c("хв")))
        )
        val fallback = pre + Schedule.fmt(st.remaining(now).plusSeconds(59))
        return Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(fallback).setDynamicValue(dyn).build())
            .setLayoutConstraintsForDynamicText(
                TypeBuilders.StringLayoutConstraint.Builder("≥ 23г 59хв")
                    .setAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .build()
            )
            .setMaxLines(1)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(37f))
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
        const val RES_VERSION = "3"
        const val STRIP_SLOTS = 24
        /** Half-hour layouts ahead (~3 h); the tile is re-requested every 15 min anyway. */
        const val TIMELINE_SLOTS = 6
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
