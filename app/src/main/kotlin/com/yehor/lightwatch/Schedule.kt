package com.yehor.lightwatch

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Чиста логіка без Android-залежностей.
 *
 * Дані: для кожної дати 48 прапорців (півгодинні слоти), true = світла НЕМАЄ.
 */
object Schedule {
    val ZONE: ZoneId = ZoneId.of("Europe/Kiev") // "Kyiv" немає в старих tzdata
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

    /** energy-ua.info: погодинна шкала; дні без повних 24 годин пропускаються. */
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
     * bezsvitla.com.ua/poltavska-oblast: картки всіх черг ("<strong>Черга 3.2</strong>"),
     * у кожній JSON в атрибутах data-schedule-intervals (сьогодні) і data-schedule-tomorrow, крок 30 хв.
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

    /** Шматок сторінки з карткою черги, де є графік. */
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

    /** Список інтервалів → 48 прапорців; null, якщо доба покрита не повністю. */
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
     * poe.pl.ua/customs/newgpv-info.php: таблиця gpv-table, рядок на чергу, 48 клітинок
     * gpv-s1 (є), gpv-s2 (немає), gpv-s3 (перемикання). Повертає 0/1/2 або null.
     */
    fun parsePoe(html: String, queue: String): IntArray? {
        // друга (вертикальна) таблиця дублює першу — беремо тільки горизонтальну
        // (обидві назви класів трапляються ще й у CSS, тому шукаємо після div gpv-hview)
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

    // ---------------------------------------------------------------- стан

    data class Status(
        /** true — світла зараз немає */
        val off: Boolean,
        /** коли почалась поточна фаза (або початок відомих даних) */
        val since: ZonedDateTime,
        /** коли зміниться стан (або кінець відомих даних) */
        val changeAt: ZonedDateTime,
        /** дані закінчились раніше, ніж змінився стан → реально щонайменше до changeAt */
        val changeIsMinimum: Boolean,
        /** кінець наступної фази, null якщо невідомо */
        val nextEnd: ZonedDateTime?,
        val nextEndIsMinimum: Boolean,
    ) {
        fun remaining(now: ZonedDateTime): Duration = Duration.between(now, changeAt)
        val nextLen: Duration? get() = nextEnd?.let { Duration.between(changeAt, it) }
        /** Частка пройденої поточної фази 0..1. */
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

    /** Моменти майбутніх змін стану (для таймлайнів), не більше [max]. */
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

    /** Наступні [n] слотів від поточного (null — немає даних). */
    fun upcoming(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime, n: Int): List<Boolean?> {
        var t = slotStart(now)
        return List(n) { at(days, t).also { t = t.plusMinutes(30) } }
    }

    /** Відрізки без світла за день: [(з, до)] у слотах, "до" може бути 48. */
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

    /** "2г 17хв" / "34хв" / "3г" */
    fun fmt(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        val h = m / 60
        val mm = m % 60
        return when {
            h > 0 && mm == 0L -> "${h}г"
            h > 0 -> "${h}г ${mm}хв"
            else -> "${mm}хв"
        }
    }

    /** Слот → "HH:MM" (48 → "24:00"). */
    fun slotTime(s: Int): String = "%02d:%02d".format(s / 2, (s % 2) * 30)
}
