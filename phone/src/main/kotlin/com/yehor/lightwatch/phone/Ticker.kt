package com.yehor.lightwatch.phone

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yehor.lightwatch.Refresh
import com.yehor.lightwatch.Schedule
import com.yehor.lightwatch.Settings
import com.yehor.lightwatch.Store
import com.yehor.lightwatch.Surfaces
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Surfaces.onChange = { c -> Ticker.refresh(c) }
    }
}

/**
 * Keeps the phone surfaces (home screen widget, live notification) in step with the schedule.
 * Both show a countdown that the system runs by itself; a redraw is only needed when the state flips,
 * when the strip shifts (every half hour), or when the live notification should appear.
 * One alarm at the nearest of those moments.
 */
object Ticker {
    fun refresh(c: Context) {
        val app = c.applicationContext
        Refresh.ensure(app)
        PowerWidget.updateAll(app)
        LiveNote.update(app)
        scheduleNext(app)
    }

    private fun scheduleNext(c: Context) {
        val now = ZonedDateTime.now(Schedule.ZONE)
        val halfHour = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(if (now.minute >= 30) 60 else 30)
        val st = Schedule.status(Store.load(c, Settings.maybeIsOff(c)), now)
        val candidates = mutableListOf(halfHour)
        if (st != null) {
            candidates += st.changeAt
            LiveNote.showFrom(c, st.changeAt)?.takeIf { it.isAfter(now) }?.let { candidates += it }
        }
        val next = candidates.min()

        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 1, Intent(c, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ms = next.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }
}

/** Tick alarm, plus reboot / app update / clock changes: redraw and reschedule. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = Ticker.refresh(c)
}
