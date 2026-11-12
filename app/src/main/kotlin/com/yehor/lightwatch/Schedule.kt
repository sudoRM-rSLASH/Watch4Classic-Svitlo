package com.yehor.lightwatch

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure logic with no Android dependencies.
 *
 * Data: 48 flags per date (half-hour slots), true = NO power.
 */
object Schedule {
    val ZONE: ZoneId = ZoneId.of("Europe/Kiev") // "Kyiv" is missing from older tzdata
    const val SLOTS = 48

    // ---------------------------------------------------------------- energy-ua.info

    private val EU_DAY_RE = Regex(
        """ch_day_title">([^<]*)</h4>(.*?)(?=ch_day_title|</main>|\z)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val EU_HOUR_RE = Regex(
        """hour_info_from">(\d\d):\d\d</i>.*?<span class="hour_status ?([^"]*)""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** energy-ua.info: hourly scale; days without all 24 hours are skipped. */
    fun parseEnergyUa(html: String, today: LocalDate): Map<LocalDate, BooleanArray> {
        val out = LinkedHashMap<LocalDate, BooleanArray>()
        for (day in EU_DAY_RE.findAll(html)) {
            val title = day.groupValues[1].lowercase()
            val date = when {
                "сьогодні" in title -> today
                "завтра" in title -> today.plusDays(1)
                else -> continue
            }
            val flags = BooleanArray(SLOTS)
            val seen = BooleanArray(24)
            for (h in EU_HOUR_RE.findAll(day.groupValues[2])) {
                val hour = h.groupValues[1].toInt()
                if (hour in 0..23) {
                    val off = "hour_active" in h.groupValues[2]
                    flags[hour * 2] = off
                    flags[hour * 2 + 1] = off
                    seen[hour] = true
                }
            }
            if (seen.all { it }) out[date] = flags
        }
        return out
    }

    // ---------------------------------------------------------------- bezsvitla.com.ua

    private val BZ_DATE_RE = Regex("""data-schedule-date="(\d{4}-\d\d-\d\d)"""")
    private val BZ_TODAY_RE = Regex("""data-schedule-intervals="([^"]*)"""")
    private val BZ_TOMORROW_RE = Regex("""data-schedule-tomorrow="([^"]*)"""")
    private val BZ_ITEM_RE = Regex(
        """\{[^{}]*?"from"\s*:\s*"(\d\d):(\d\d)"[^{}]*?"to"\s*:\s*"(\d\d):(\d\d)"[^{}]*?"state"\s*:\s*"([A-Za-z_]+)"[^{}]*?\}"""
    )

    /**
     * bezsvitla.com.ua/poltavska-oblast: cards for all queues ("<strong>Черга 3.2</strong>"),
     * each with JSON in data-schedule-intervals (today) and data-schedule-tomorrow, 30-minute steps.
     */
    fun parseBezsvitla(page: String, today: LocalDate, queue: String): Map<LocalDate, BooleanArray> {
        val html = bezsvitlaCard(page, queue) ?: return emptyMap()
        val out = LinkedHashMap<LocalDate, BooleanArray>()
        val date = BZ_DATE_RE.find(html)?.groupValues?.get(1)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        BZ_TODAY_RE.find(html)?.let { m -> intervals(unescape(m.groupValues[1]))?.let { out[date] = it } }
        BZ_TOMORROW_RE.find(html)?.let { m ->
            intervals(unescape(m.groupValues[1]))?.let { out[date.plusDays(1)] = it }
        }
        return out
    }

    /** The part of the page with the queue's card that holds the schedule. */
    private fun bezsvitlaCard(page: String, queue: String): String? {
        val marker = "<strong>Черга $queue</strong>"
        var i = page.indexOf(marker)
        while (i >= 0) {
            val end = page.indexOf("<strong>Черга ", i + marker.length).let { if (it < 0) page.length else it }
            val seg = page.substring(i, end)
            if ("data-schedule-intervals" in seg) return seg
            i = page.indexOf(marker, i + marker.length)
        }
        return null
    }

    /** Interval list to 48 flags; null if the day is not fully covered. */
    private fun intervals(json: String): BooleanArray? {
        val flags = BooleanArray(SLOTS)
        val seen = BooleanArray(SLOTS)
        for (m in BZ_ITEM_RE.findAll(json)) {
            val (fh, fm, th, tm, state) = m.destructured
            val from = fh.toInt() * 2 + fm.toInt() / 30
            val to = (th.toInt() * 2 + tm.toInt() / 30).coerceAtMost(SLOTS)
            val off = !state.equals("ON", ignoreCase = true)
            for (s in from until to) if (s in 0 until SLOTS) { flags[s] = off; seen[s] = true }
        }
        return if (seen.all { it }) flags else null
    }

    private fun unescape(s: String) = s
        .replace("&quot;", "\"").replace("&#34;", "\"").replace("&#039;", "'").replace("&amp;", "&")

    // ---------------------------------------------------------------- poe.pl.ua

    private val POE_ROW_RE = Regex(
        """<td class="gpv-q"[^>]*>\s*(\d\.\d)\s*</td>(.*?)</tr>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val POE_CELL_RE = Regex("""<td class="gpv-c gpv-s(\d)""")

    /**
     * poe.pl.ua/customs/newgpv-info.php: a gpv-table with one row per queue, 48 cells
     * gpv-s1 (on), gpv-s2 (off), gpv-s3 (switching). Returns 0/1/2 or null.
     */
    fun parsePoe(html: String, queue: String): IntArray? {
        // the second (vertical) table duplicates the first: use only the horizontal one
        // (both class names also appear in the CSS, so search after the gpv-hview div)
        val start = html.indexOf("class=\"gpv-hview").let { if (it < 0) 0 else it }
        val end = html.indexOf("class=\"gpv-vview", start).let { if (it < 0) html.length else it }
        val h = html.substring(start, end)
        for (row in POE_ROW_RE.findAll(h)) {
            if (row.groupValues[1] != queue) continue
            val cells = POE_CELL_RE.findAll(row.groupValues[2]).map { it.groupValues[1] }.toList()
            if (cells.size != SLOTS) return null
            return IntArray(SLOTS) { i -> when (cells[i]) { "1" -> 0; "3" -> 2; else -> 1 } }
        }
        return null
    }

    // ---------------------------------------------------------------- state

    data class Status(
        /** true: no power right now */
        val off: Boolean,
        /** when the current phase started (or where the known data begins) */
        val since: ZonedDateTime,
        /** when the state changes (or where the known data ends) */
        val changeAt: ZonedDateTime,
        /** data ran out before the state changed, so it really lasts at least until changeAt */
        val changeIsMinimum: Boolean,
        /** end of the next phase, null if unknown */
        val nextEnd: ZonedDateTime?,
        val nextEndIsMinimum: Boolean,
    ) {
        fun remaining(now: ZonedDateTime): Duration = Duration.between(now, changeAt)
        val nextLen: Duration? get() = nextEnd?.let { Duration.between(changeAt, it) }
        /** Elapsed fraction of the current phase, 0..1. */
        fun progress(now: ZonedDateTime): Float {
            val total = Duration.between(since, changeAt).seconds.toFloat()
            if (total <= 0f) return 0f
            return (Duration.between(since, now).seconds / total).coerceIn(0f, 1f)
        }
    }

    fun slotOf(t: ZonedDateTime): Int = t.hour * 2 + t.minute / 30

    private fun slotStart(t: ZonedDateTime): ZonedDateTime =
        t.truncatedTo(ChronoUnit.HOURS).plusMinutes(if (t.minute >= 30) 30 else 0)

    fun at(days: Map<LocalDate, BooleanArray>, t: ZonedDateTime): Boolean? =
        days[t.toLocalDate()]?.getOrNull(slotOf(t))

    private const val LIMIT = 48 * 3

    fun status(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime): Status? {
        val cur = at(days, now) ?: return null
        val slot = slotStart(now)

        var since = slot
        for (i in 0 until LIMIT) {
            val prev = since.minusMinutes(30)
            if (at(days, prev) != cur) break
            since = prev
        }

        var t = slot.plusMinutes(30)
        var change: ZonedDateTime? = null
        for (i in 0 until LIMIT) {
            val s = at(days, t) ?: break
            if (s != cur) { change = t; break }
            t = t.plusMinutes(30)
        }
        if (change == null) return Status(cur, since, t, true, null, false)

        var u = change.plusMinutes(30)
        var end: ZonedDateTime? = null
        for (i in 0 until LIMIT) {
            val s = at(days, u) ?: break
            if (s == cur) { end = u; break }
            u = u.plusMinutes(30)
        }
        return Status(cur, since, change, false, end ?: u, end == null)
    }

    /** Times of upcoming state changes (for timelines), at most [max]. */
    fun changes(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime, max: Int = 8): List<ZonedDateTime> {
        val out = ArrayList<ZonedDateTime>()
        var prev = at(days, now) ?: return out
        var t = slotStart(now).plusMinutes(30)
        for (i in 0 until LIMIT) {
            val s = at(days, t) ?: break
            if (s != prev) { out += t; if (out.size >= max) break }
            prev = s
            t = t.plusMinutes(30)
        }
        return out
    }

    /** The next [n] slots from the current one (null = no data). */
    fun upcoming(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime, n: Int): List<Boolean?> {
        var t = slotStart(now)
        return List(n) { at(days, t).also { t = t.plusMinutes(30) } }
    }

    /** Outage ranges for a day: [(from, to)] in slots, "to" may be 48. */
    fun offRanges(flags: BooleanArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < flags.size) {
            if (flags[i]) {
                val s = i
                while (i < flags.size && flags[i]) i++
                out += s to i
            } else i++
        }
        return out
    }

    fun offDuration(flags: BooleanArray): Duration = Duration.ofMinutes(flags.count { it } * 30L)

    /** Slot to "HH:MM" (48 → "24:00"). */
    fun slotTime(s: Int): String = "%02d:%02d".format(s / 2, (s % 2) * 30)
}
