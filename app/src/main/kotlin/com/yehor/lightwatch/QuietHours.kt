package com.yehor.lightwatch

/** Pure logic for the "quiet hours" setting: a window of whole hours that may wrap around midnight. */
object QuietHours {
    /** Hours the picker offers as ready-made windows: (from, to) with "to" exclusive. */
    val PRESETS = listOf(22 to 7, 23 to 7, 0 to 7, 0 to 8)

    /** [from] == [to] means quiet hours are off. A window like 23 → 7 covers the night. */
    fun enabled(from: Int, to: Int): Boolean = from != to

    /** Whether the hour of the day [hour] (0..23) is inside the window [from, to). */
    fun active(from: Int, to: Int, hour: Int): Boolean = when {
        from == to -> false
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }
}
