package com.yehor.lightwatch

import android.content.Context
import java.time.Duration

/** Localized formatting that needs string resources (Schedule stays free of Android dependencies). */
object Fmt {
    /** "2h 17m" / "34m" / "3h" in the current language. */
    fun duration(c: Context, d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        val h = m / 60
        val mm = m % 60
        return when {
            h > 0 && mm == 0L -> c.getString(R.string.duration_h, h)
            h > 0 -> c.getString(R.string.duration_hm, h, mm)
            else -> c.getString(R.string.duration_m, mm)
        }
    }
}
