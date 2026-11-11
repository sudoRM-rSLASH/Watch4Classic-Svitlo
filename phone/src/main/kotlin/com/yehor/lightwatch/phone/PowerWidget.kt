package com.yehor.lightwatch.phone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.yehor.lightwatch.Palette
import com.yehor.lightwatch.Schedule
import com.yehor.lightwatch.Settings
import com.yehor.lightwatch.Store
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Home screen widget. Small (2x2): status, countdown, change time.
 * Wide: plus a 12-hour strip and the last update time.
 * The countdown is a Chronometer in count-down mode, so the launcher ticks it without waking the app.
 */
class PowerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Ticker.refresh(c)

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle) =
        updateAll(c)

    companion object {
        private const val STRIP_SLOTS = 24
        private const val SMALL_SLOTS = 12

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, PowerWidget::class.java))
            if (ids.isEmpty()) return
            val views = if (Build.VERSION.SDK_INT >= 31)
                // the launcher picks the largest entry that fits the widget's current size
                RemoteViews(mapOf(
                    SizeF(40f, 40f) to build(c, Kind.TINY),
                    SizeF(100f, 40f) to build(c, Kind.ROW),
                    SizeF(100f, 120f) to build(c, Kind.SMALL),
                    SizeF(220f, 120f) to build(c, Kind.WIDE),
                ))
            else build(c, Kind.WIDE)
            m.updateAppWidget(ids, views)
        }

        private enum class Kind(val layout: Int) {
            TINY(R.layout.widget_tiny), ROW(R.layout.widget_row), SMALL(R.layout.widget_small), WIDE(R.layout.widget)
        }

        private fun build(c: Context, kind: Kind): RemoteViews {
            val v = RemoteViews(c.packageName, kind.layout)
            val wide = kind == Kind.WIDE
            val tiny = kind == Kind.TINY
            val now = ZonedDateTime.now(Schedule.ZONE)
            val days = Store.load(c, Settings.maybeIsOff(c))
            val maybe = Store.loadMaybe(c)
            val st = Schedule.status(days, now)
            val stale = Store.isStale(c) || Store.error(c) != null

            if (kind == Kind.SMALL || wide) v.setTextViewText(R.id.queue, "черга ${Settings.queue(c)}")
            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )

            if (st == null) {
                if (!tiny && kind != Kind.ROW) {
                    v.setTextViewText(R.id.status, "СВІТЛО")
                    v.setTextColor(R.id.status, Palette.LABEL)
                }
                v.setInt(R.id.root, "setBackgroundResource", R.drawable.widget_bg_none)
                v.setInt(R.id.icon, "setColorFilter", Palette.LABEL)
                if (!tiny) {
                    v.setViewVisibility(R.id.countdown, View.GONE)
                    v.setViewVisibility(R.id.nodata, View.VISIBLE)
                }
                v.setTextViewText(R.id.at, if (tiny) "—" else "відкрий, щоб оновити")
            } else {
                val switching = Schedule.at(maybe, now) == true
                val color = when { switching -> Palette.AMBER; st.off -> Palette.RED; else -> Palette.GREEN }
                if (kind == Kind.SMALL || wide) v.setTextViewText(
                    R.id.status,
                    (when { switching -> "ПЕРЕМИКАННЯ"; st.off -> "СВІТЛА НЕМАЄ"; else -> "СВІТЛО Є" }) + if (stale) " ?" else ""
                )
                if (kind == Kind.SMALL || wide) v.setTextColor(R.id.status, color)
                v.setInt(R.id.icon, "setColorFilter", color)
                v.setInt(
                    R.id.root, "setBackgroundResource",
                    when { switching -> R.drawable.widget_bg_amber; st.off -> R.drawable.widget_bg_red; else -> R.drawable.widget_bg_green }
                )
                if (!tiny) v.setViewVisibility(R.id.countdown, View.VISIBLE)
                if (!tiny) v.setViewVisibility(R.id.nodata, View.GONE)
                val at = hhmm(st.changeAt, now)
                if (tiny) {
                    // no room for a countdown: just the time of the next change
                    v.setTextViewText(R.id.at, if (st.changeIsMinimum) "≥$at" else at)
                } else {
                // base in the elapsedRealtime clock: the chronometer counts down to it
                val left = st.changeAt.toInstant().toEpochMilli() - System.currentTimeMillis()
                v.setChronometer(
                    R.id.countdown, SystemClock.elapsedRealtime() + left,
                    if (st.changeIsMinimum) "≥ %s" else null, true
                )
                v.setChronometerCountDown(R.id.countdown, true)
                v.setTextViewText(
                    R.id.at,
                    when { st.changeIsMinimum -> "без змін до $at"; st.off -> "увімкнуть о $at"; else -> "вимкнуть о $at" }
                )
                }
            }
            if (tiny || kind == Kind.ROW) return v

            // strip: 12 h on the wide widget, 6 h on the small one
            val slots = if (wide) STRIP_SLOTS else SMALL_SLOTS
            v.removeAllViews(R.id.strip)
            run {
                val off = Schedule.upcoming(days, now, slots)
                val m = Schedule.upcoming(maybe, now, slots)
                for (i in 0 until slots) {
                    val cell = RemoteViews(c.packageName, R.layout.widget_cell)
                    val color = when {
                        m[i] == true -> Palette.AMBER
                        off[i] == null -> Palette.NODATA
                        off[i] == true -> Palette.RED
                        else -> Palette.GREEN
                    }
                    cell.setInt(R.id.cell, "setColorFilter", color)
                    // the current slot is bright, the rest slightly dimmed
                    cell.setInt(R.id.cell, "setImageAlpha", if (i == 0) 255 else 190)
                    v.addView(R.id.strip, cell)
                }
            }

            if (wide) {
                // hour marks at the start, middle and end of the strip
                val start = now.withMinute(if (now.minute < 30) 0 else 30).withSecond(0).withNano(0)
                v.setTextViewText(R.id.h0, "зараз")
                v.setTextViewText(R.id.h1, hhmm(start.plusHours(6), now))
                v.setTextViewText(R.id.h2, hhmm(start.plusHours(12), now))
                val fetched = Store.fetchedAt(c)
                v.setTextViewText(
                    R.id.updated,
                    if (fetched == 0L) "ще не оновлювалось"
                    else (if (stale) "⚠ дані від " else "оновлено ") +
                        Instant.ofEpochMilli(fetched).atZone(Schedule.ZONE).let {
                            if (it.toLocalDate() == now.toLocalDate()) hhmm(it, now)
                            else "%02d.%02d %s".format(it.dayOfMonth, it.monthValue, hhmm(it, now))
                        }
                )
                v.setTextColor(R.id.updated, if (stale) Palette.AMBER else Palette.LABEL)
            }
            return v
        }

        private fun hhmm(t: ZonedDateTime, now: ZonedDateTime): String =
            if (t.hour == 0 && t.minute == 0 && t.toLocalDate() != now.toLocalDate()) "24:00"
            else "%02d:%02d".format(t.hour, t.minute)
    }
}
