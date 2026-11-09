package com.yehor.lightwatch

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Reminders before the power goes off / comes back.
 * At most one alarm is scheduled at a time, the nearest one. After it fires,
 * after a schedule refresh or a settings change, it is rescheduled ([schedule]).
 */
object Notify {
    /** Lead time options in minutes; 0 = off. */
    val LEADS = listOf(0, 5, 10, 15, 20, 30, 45, 60)

    private const val CHANNEL = "light_changes"
    private const val EXTRA_OFF = "off"
    private const val EXTRA_AT = "at"

    fun leadLabel(m: Int) = if (m == 0) "не нагадувати" else "за $m хв"

    /**
     * Next event under the current settings: (notification time, power going off?, change time).
     * If the change is already closer than [offLead]/[onLead], notify right away (time = [now]),
     * but not twice: the event at [shownAt] (epoch ms) has already been shown.
     */
    fun next(
        days: Map<java.time.LocalDate, BooleanArray>, now: ZonedDateTime, offLead: Int, onLead: Int,
        shownAt: Long = 0L,
    ): Triple<ZonedDateTime, Boolean, ZonedDateTime>? {
        if (offLead == 0 && onLead == 0) return null
        for (t in Schedule.changes(days, now, max = 16)) {
            val off = Schedule.at(days, t) ?: continue
            val lead = if (off) offLead else onLead
            if (lead == 0) continue
            if (t.toInstant().toEpochMilli() == shownAt) continue
            val fire = t.minusMinutes(lead.toLong())
            return Triple(if (fire.isAfter(now)) fire else now, off, t)
        }
        return null
    }

    /** Whether the app can post notifications now (permission granted and not turned off in the system). */
    fun enabled(c: Context): Boolean =
        c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun prefs(c: Context) = c.getSharedPreferences("notify", Context.MODE_PRIVATE)
    private fun shownAt(c: Context) = prefs(c).getLong("shownAt", 0L)
    private fun markShown(c: Context, at: ZonedDateTime) {
        prefs(c).edit().putLong("shownAt", at.toInstant().toEpochMilli()).apply()
    }

    /**
     * Whether the event still holds in the current schedule: state [off] at [at], and the opposite before it.
     * The previous day may be missing from the cache (null): then trust the scheduling.
     */
    fun stillValid(days: Map<java.time.LocalDate, BooleanArray>, at: ZonedDateTime, off: Boolean): Boolean =
        Schedule.at(days, at) == off && Schedule.at(days, at.minusMinutes(30)) != off

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
        val ev = next(
            Store.load(c, Settings.maybeIsOff(c)), ZonedDateTime.now(Schedule.ZONE),
            Settings.notifyOff(c), Settings.notifyOn(c), shownAt(c)
        ) ?: return
        val (fire, off, at) = ev
        val intent = Intent(c, AlarmReceiver::class.java)
            .putExtra(EXTRA_OFF, off).putExtra(EXTRA_AT, at.toInstant().toEpochMilli())
        val p = PendingIntent.getBroadcast(
            c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ms = fire.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        else
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
    }

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Зміна світла", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Нагадування перед вимкненням і увімкненням"
                enableVibration(true)
            }
        )
    }

    internal fun show(c: Context, off: Boolean, at: ZonedDateTime) {
        channel(c)
        val now = ZonedDateTime.now(Schedule.ZONE)
        val min = ((java.time.Duration.between(now, at).seconds + 59) / 60).coerceAtLeast(0)
        val hm = "%02d:%02d".format(at.hour, at.minute)
        val title = if (off) "Світло вимкнуть о $hm" else "Світло увімкнуть о $hm"
        val text = if (min > 0) "через $min хв · черга ${Settings.queue(c)}" else "черга ${Settings.queue(c)}"
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(c, CHANNEL)
            .setSmallIcon(if (off) R.drawable.ic_bolt_off_mono else R.drawable.ic_bolt_mono)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setTimeoutAfter(java.time.Duration.between(now, at).toMillis().coerceAtLeast(60_000L))
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(1, n) }
        markShown(c, at)
    }
}

/** Notification alarm; also reschedules on reboot, app update and clock/time zone changes. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.hasExtra("at")) {
            val at = Instant.ofEpochMilli(intent.getLongExtra("at", 0)).atZone(Schedule.ZONE)
            // the schedule may have changed since scheduling: notify only if the event still holds
            val days = Store.load(c, Settings.maybeIsOff(c))
            val off = intent.getBooleanExtra("off", false)
            // the alarm may arrive late (doze, reboot, clock change): don't notify about the past
            val late = !ZonedDateTime.now(Schedule.ZONE).isBefore(at)
            if (!late && Notify.stillValid(days, at, off)) Notify.show(c, off, at)
        }
        Notify.schedule(c)
    }
}
