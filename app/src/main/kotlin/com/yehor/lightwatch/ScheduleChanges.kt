package com.yehor.lightwatch

import java.time.LocalDate

/**
 * Pure logic: compares the cached schedule with the one that was just saved, to tell the user about
 * "today's schedule changed" and "tomorrow's schedule appeared".
 */
object ScheduleChanges {
    /** Outage ranges (in slots, "to" may be 48) that appeared and that disappeared. */
    data class Diff(val added: List<Pair<Int, Int>>, val removed: List<Pair<Int, Int>>)

    /**
     * [today]: the rest of today changed; [tomorrow]: the "no power" flags of tomorrow, set only when
     * tomorrow's schedule was not in the cache before.
     */
    data class Result(val today: Diff?, val tomorrow: BooleanArray?)

    /**
     * [old] and [fresh] are slot states (0 on, 1 off, 2 yellow) per day; [fromSlot] is the current slot:
     * what already happened today does not count as a change. Yellow slots count as no power when
     * [maybeIsOff], the same way as everywhere else, so a change that is invisible to the user is not reported.
     * With an empty [old] (first fetch, or the cache was cleared after changing the source or the queue)
     * there is nothing to compare with and nothing is reported.
     */
    fun detect(
        old: Map<LocalDate, IntArray>, fresh: Map<LocalDate, IntArray>,
        today: LocalDate, fromSlot: Int, maybeIsOff: Boolean,
    ): Result {
        if (old.isEmpty()) return Result(null, null)
        val before = Store.flagsOf(old, maybeIsOff)
        val after = Store.flagsOf(fresh, maybeIsOff)

        val a = before[today]
        val b = after[today]
        val todayDiff = if (a != null && b != null) diff(a, b, fromSlot) else null

        val tomorrow = today.plusDays(1)
        return Result(todayDiff, if (tomorrow !in old) after[tomorrow] else null)
    }

    /** Outage slots from [fromSlot] on that were added / removed, as ranges; null if nothing changed. */
    fun diff(old: BooleanArray, fresh: BooleanArray, fromSlot: Int): Diff? {
        val n = minOf(old.size, fresh.size)
        val added = BooleanArray(n) { it >= fromSlot && fresh[it] && !old[it] }
        val removed = BooleanArray(n) { it >= fromSlot && old[it] && !fresh[it] }
        val d = Diff(Schedule.offRanges(added), Schedule.offRanges(removed))
        return if (d.added.isEmpty() && d.removed.isEmpty()) null else d
    }
}
