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
 * Complication для циферблата.
 *  SHORT_TEXT  : іконка (⚡ / ⚡ перекреслена) + відлік + "→вимк"/"→увімк"
 *  LONG_TEXT   : "Світло є · вимк о 14:00" + відлік
 *  RANGED_VALUE: дуга — скільки пройшло від поточної фази, у центрі відлік
 * Відлік рахує сам циферблат; таймлайн перемикає стан рівно в момент зміни.
 */
class LightComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = ZonedDateTime.now(Schedule.ZONE)
        val h = now.truncatedTo(ChronoUnit.HOURS)
        val st = Schedule.Status(false, h.minusHours(1), h.plusHours(2), false, h.plusHours(6), false)
        return build(type, st, now)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        Refresh.ifStale(this)
        val type = request.complicationType
        val days = Store.load(this)
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
     * SHORT_TEXT / RANGED_VALUE (маленьке коло):
     *   заголовок — поточний стан "ON"/"OFF" (влазить ~5 символів), текст — скільки ще "5:54" / "0:37"
     *   (динамічний текст: годинник сам перераховує щохвилини; без підтримки — статичний "5:54" на момент запиту),
     *   іконка — блискавка (перекреслена, коли світла немає), дуга — пройдена частина фази.
     * LONG_TEXT: заголовок — "OFF · ще 5:54", текст — "Світло з 01:00" / "Вимкнуть о 14:00".
     */
    private fun build(type: ComplicationType, st: Schedule.Status?, now: ZonedDateTime): ComplicationData? {
        if (st == null) return noData(type)

        val prefix = if (st.changeIsMinimum) ">" else ""
        fun countdown(before: String = ""): ComplicationText = hmCountdown(st.changeAt, now, before + prefix)

        val stateEn = if (st.off) "OFF" else "ON"
        val at = hhmm(st.changeAt, now)
        val stateUa = if (st.off) "Немає світла" else "Світло є"
        val desc = text(
            if (st.changeIsMinimum) "$stateUa, без змін щонайменше до $at"
            else "$stateUa, ${if (st.off) "увімкнуть" else "вимкнуть"} о $at"
        )
        val img = icon(st.off)

        // маленький формат
        val shortTitle: ComplicationText = text(stateEn)
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
                        st.changeIsMinimum -> "$stateUa до $at+"
                        st.off -> "Світло з $at"
                        else -> "Вимкнуть о $at"
                    }
                ),
                desc
            )
                .setTitle(countdown("$stateEn · ще "))
                .setMonochromaticImage(img)
                .setTapAction(tap())
                .build()
            else -> null
        }
    }

    /**
     * Відлік "H:MM" до [target]. Хвилини округлюємо вгору (+59 с), як годинник робить для таймерів:
     * за 30 с до зміни покаже "0:01", а не "0:00".
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
        val desc = text("Немає даних про світло")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(dash, desc)
                .setMonochromaticImage(icon(false)).setTapAction(tap()).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("Немає даних"), desc)
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
        /** Викликається після оновлення кешу, щоб циферблат одразу підхопив нові дані. */
        fun requestUpdate(c: Context) {
            ComplicationDataSourceUpdateRequester
                .create(c, ComponentName(c, LightComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
