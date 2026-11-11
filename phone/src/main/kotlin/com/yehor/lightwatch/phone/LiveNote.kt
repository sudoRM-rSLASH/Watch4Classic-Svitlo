package com.yehor.lightwatch.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yehor.lightwatch.Schedule
import com.yehor.lightwatch.Settings
import com.yehor.lightwatch.Store
import com.yehor.lightwatch.core.R as CoreR
import java.time.ZonedDateTime

/**
 * Ongoing notification with a live countdown to the next change ("Світла немає · увімкнуть о 03:00 · 1:29").
 * On Android 16+ it asks to be promoted (Live Updates): a status bar chip, and on Samsung the Now Bar
 * on the lock screen, if the system accepts it. Elsewhere it is a regular silent ongoing notification.
 */
object LiveNote {
    /** Modes: -1 = off, 0 = always, N > 0 = only from N minutes before a change. */
    val MODES = listOf(-1, 0, 15, 30, 60, 120)
    private const val ID = 2
    private const val CHANNEL = "live_countdown"

    fun label(mode: Int) = when (mode) {
        -1 -> "вимкнено"
        0 -> "завжди"
        else -> if (mode % 60 == 0) "за ${mode / 60} год до зміни" else "за $mode хв до зміни"
    }

    private fun prefs(c: Context) = c.getSharedPreferences("phone", Context.MODE_PRIVATE)
    fun mode(c: Context): Int = prefs(c).getInt("live", -1)
    fun setMode(c: Context, m: Int) { prefs(c).edit().putInt("live", m).apply() }

    /** When the notification should appear before a change at [changeAt]; null if it is off. */
    fun showFrom(c: Context, changeAt: ZonedDateTime): ZonedDateTime? = when (val m = mode(c)) {
        -1 -> null
        0 -> changeAt.minusYears(1)
        else -> changeAt.minusMinutes(m.toLong())
    }

    fun update(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val now = ZonedDateTime.now(Schedule.ZONE)
        val st = Schedule.status(Store.load(c, Settings.maybeIsOff(c)), now)
        val from = st?.let { showFrom(c, it.changeAt) }
        if (st == null || from == null || now.isBefore(from) || !nm.areNotificationsEnabled()) {
            nm.cancel(ID)
            return
        }
        channel(nm)
        val switching = Schedule.at(Store.loadMaybe(c), now) == true
        val at = "%02d:%02d".format(st.changeAt.hour, st.changeAt.minute)
        val state = when { switching -> "Перемикання"; st.off -> "Світла немає"; else -> "Світло є" }
        val next = when { st.changeIsMinimum -> "без змін щонайменше до $at"; st.off -> "увімкнуть о $at"; else -> "вимкнуть о $at" }
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(if (st.off) CoreR.drawable.ic_bolt_off_mono else CoreR.drawable.ic_bolt_mono)
            .setContentTitle("$state · $next")
            .setContentText("Черга ${Settings.queue(c)}" + if (Store.isStale(c)) " · ⚠ дані застарілі" else "")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(true)
            .setWhen(st.changeAt.toInstant().toEpochMilli())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        if (Build.VERSION.SDK_INT >= 36) promote(b)
        nm.notify(ID, b.build())
    }

    /** Android 16 Live Updates; called by reflection because the app compiles against API 35. */
    private fun promote(b: Notification.Builder) {
        runCatching {
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(b, true)
        }
    }

    private fun channel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Відлік до зміни", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Постійне сповіщення з відліком (Now Bar на Samsung)"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
