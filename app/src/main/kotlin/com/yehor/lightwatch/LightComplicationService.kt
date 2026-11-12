package com.yehor.lightwatch

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.DynamicComplicationText
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Watch face complication.
 *  SHORT_TEXT  : icon (⚡ / struck-through ⚡) + state ON/OFF + countdown
 *  LONG_TEXT   : "Power off at 14:00" + title "ON · in 5:54"
 *  RANGED_VALUE: arc = elapsed part of the current phase, countdown in the centre
 * The watch face runs the countdown itself; the timeline switches state exactly at the change.
 */
class LightComplicationService : SuspendingTimelineComplicationDataSourceService() {
    /** Data is stale: append "?" to ON/OFF. */
    private var stale = false

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = ZonedDateTime.now(Schedule.ZONE)
        val h = now.truncatedTo(ChronoUnit.HOURS)
        val st = Schedule.Status(false, h.minusHours(1), h.plusHours(2), false, h.plusHours(6), false)
        return build(type, st, now)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        Refresh.ifStale(this)
        val type = request.complicationType
        val days = Store.load(this, Settings.maybeIsOff(this))
        stale = Store.isStale(this)
        val now = ZonedDateTime.now(Schedule.ZONE)
        val default = build(type, Schedule.status(days, now), now) ?: return null

        val changes = Schedule.changes(days, now)
        val entries = changes.mapIndexedNotNull { i, t ->
            val end = changes.getOrNull(i + 1) ?: t.plusHours(24)
            val data = build(type, Schedule.status(days, t), t) ?: return@mapIndexedNotNull null
            TimelineEntry(TimeInterval(t.toInstant(), end.toInstant()), data)
        }
        return ComplicationDataTimeline(default, entries)
    }

    /**
     * SHORT_TEXT / RANGED_VALUE (small circle):
     *   title: current state "ON"/"OFF" (~5 chars fit), text: time left "5:54" / "0:37"
     *   (dynamic text: the watch recomputes it every minute; without support, a static "5:54" as of the request),
     *   icon: a bolt (struck through when the power is off), arc: elapsed part of the phase.
     * LONG_TEXT: title "OFF · in 5:54", text "Power from 01:00" / "Power off at 14:00" (localized).
     */
    private fun build(type: ComplicationType, st: Schedule.Status?, now: ZonedDateTime): ComplicationData? {
        if (st == null) return noData(type)

        val prefix = if (st.changeIsMinimum) ">" else ""
        fun countdown(before: String = ""): ComplicationText = hmCountdown(st.changeAt, now, before + prefix)

        val stateTag = getString(if (st.off) R.string.complication_off else R.string.complication_on) +
            if (stale) "?" else ""
        val at = hhmm(st.changeAt, now)
        val stateName = getString(if (st.off) R.string.complication_power_off else R.string.complication_power_on)
        val descText = getString(
            when {
                st.changeIsMinimum -> R.string.complication_desc_no_change
                st.off -> R.string.complication_desc_returns
                else -> R.string.complication_desc_goes_off
            },
            stateName, at
        )
        val desc = text(if (stale) getString(R.string.complication_desc_stale, descText) else descText)
        val img = icon(st.off)

        // small format
        val shortTitle: ComplicationText = text(stateTag)
        val shortText: ComplicationText =
            countdown()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(shortText, desc)
                .setTitle(shortTitle)
                .setMonochromaticImage(img)
                .setTapAction(tap())
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                st.progress(now) * 100f, 0f, 100f, desc
            )
                .setText(shortText)
                .setTitle(shortTitle)
                .setMonochromaticImage(img)
                .setTapAction(tap())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(
                    when {
                        st.changeIsMinimum -> getString(R.string.complication_long_until, stateName, at)
                        st.off -> getString(R.string.complication_long_power_from, at)
                        else -> getString(R.string.complication_long_off_at, at)
                    }
                ),
                desc
            )
                .setTitle(countdown(getString(R.string.complication_title_prefix, stateTag)))
                .setMonochromaticImage(img)
                .setTapAction(tap())
                .build()
            else -> null
        }
    }

    /**
     * "H:MM" countdown to [target]. Minutes are rounded up (+59 s), as the watch does for timers:
     * 30 s before the change it shows "0:01", not "0:00".
     */
    private fun hmCountdown(target: ZonedDateTime, now: ZonedDateTime, prefix: String): ComplicationText {
        val dur = DynamicInstant.platformTimeWithSecondsPrecision()
            .durationUntil(DynamicInstant.withSecondsPrecision(target.toInstant().plusSeconds(59)))
        val twoDigits = DynamicInt32.IntFormatter.Builder().setMinIntegerDigits(2).build()
        val dyn = DynamicString.constant(prefix)
            .concat(dur.toIntHours().format())
            .concat(DynamicString.constant(":"))
            .concat(dur.minutesPart.format(twoDigits))
        val m = (java.time.Duration.between(now, target).seconds + 59).coerceAtLeast(0) / 60
        val fallback = "$prefix${m / 60}:%02d".format(m % 60)
        return DynamicComplicationText(dyn, fallback)
    }

    private fun hhmm(t: ZonedDateTime, now: ZonedDateTime): String =
        if (t.hour == 0 && t.minute == 0 && t.toLocalDate() != now.toLocalDate()) "24:00"
        else "%02d:%02d".format(t.hour, t.minute)

    private fun noData(type: ComplicationType): ComplicationData? {
        val dash = text("—")
        val desc = text(getString(R.string.complication_no_data_desc))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(dash, desc)
                .setMonochromaticImage(icon(false)).setTapAction(tap()).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(getString(R.string.no_data)), desc)
                .setMonochromaticImage(icon(false)).setTapAction(tap()).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(0f, 0f, 100f, desc)
                .setText(dash).setMonochromaticImage(icon(false)).setTapAction(tap()).build()
            else -> null
        }
    }

    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    private fun tap(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun icon(off: Boolean): MonochromaticImage =
        MonochromaticImage.Builder(
            Icon.createWithResource(this, if (off) R.drawable.ic_bolt_off_mono else R.drawable.ic_bolt_mono)
        ).build()

    companion object {
        /** Called after the cache is updated so the watch face picks up new data right away. */
        fun requestUpdate(c: Context) {
            ComplicationDataSourceUpdateRequester
                .create(c, ComponentName(c, LightComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
