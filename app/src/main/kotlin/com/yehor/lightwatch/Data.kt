package com.yehor.lightwatch

import android.content.Context
import androidx.wear.tiles.TileService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Where the schedule comes from. Slot states: 0 = power on, 1 = off, 2 = switching (may be off). */
enum class Source(val host: String) {
    POE("poe.pl.ua"),
    BEZSVITLA("bezsvitla.com.ua"),
    ENERGY_UA("energy-ua.info");

    /**
     * Dates a [load] call asks the source about. A date that is requested but missing from the result
     * has no (or no longer has a) published schedule. [hour] is the current hour in [Schedule.ZONE].
     */
    fun requested(today: LocalDate, hour: Int): List<LocalDate> =
        // POE publishes tomorrow's schedule in the evening, so it is not requested before noon
        if (this == POE && hour < 12) listOf(today) else listOf(today, today.plusDays(1))

    fun load(
        queue: String, today: LocalDate, hour: Int = java.time.LocalTime.now(Schedule.ZONE).hour,
    ): Map<LocalDate, IntArray> = when (this) {
        POE -> {
            // newgpv-info.php returns an HTML table of all queues for the date; empty means not published yet
            val out = LinkedHashMap<LocalDate, IntArray>()
            for (d in requested(today, hour)) {
                val body = "seldate=" + java.net.URLEncoder.encode(
                    """{"date_in":"${d.format(POE_DATE)}"}""", "UTF-8"
                )
                val html = Http.request("https://www.poe.pl.ua/customs/newgpv-info.php", body)
                Schedule.parsePoe(html, queue)?.let { out[d] = it }
            }
            out
        }
        BEZSVITLA -> Schedule.parseBezsvitla(Http.request("https://bezsvitla.com.ua/poltavska-oblast"), today, queue)
            .mapValues { bits(it.value) }
        ENERGY_UA -> Schedule.parseEnergyUa(Http.request("https://energy-ua.info/cherga/${queue.replace('.', '-')}"), today)
            .mapValues { bits(it.value) }
    }

    private companion object {
        val POE_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        fun bits(f: BooleanArray) = IntArray(f.size) { if (f[it]) 1 else 0 }
    }
}

object Http {
    /** GET, or a form-urlencoded POST when [form] is given. */
    fun request(url: String, form: String? = null): String {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "LightWatch/1.0 (personal Wear OS tile)")
            conn.setRequestProperty("Accept-Language", "uk")
            if (form != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 403 || code == 503) error("Сайт заблокував запит (HTTP $code)")
            if (code != 200) error("HTTP $code")
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if ("Just a moment" in html) error("Сайт вимагає перевірку браузера")
            return html
        } finally {
            conn.disconnect()
        }
    }
}

/** Settings: source + queue, display and notification options. */
object Settings {
    val QUEUES = (1..6).flatMap { q -> (1..2).map { "$q.$it" } }

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun source(c: Context): Source =
        runCatching { Source.valueOf(p(c).getString("source", null)!!) }.getOrDefault(Source.POE)

    fun queue(c: Context): String = p(c).getString("queue", null)?.takeIf { it in QUEUES } ?: "3.2"

    /** Whether "switching" (yellow) slots count as no power in the countdown (tile and complication). Defaults to true. */
    fun maybeIsOff(c: Context): Boolean = p(c).getBoolean("maybeOff", true)

    fun setMaybeIsOff(c: Context, v: Boolean) { p(c).edit().putBoolean("maybeOff", v).apply() }

    /** How many minutes before power goes off / on to notify (0 = don't notify). */
    fun notifyOff(c: Context): Int = p(c).getInt("notifyOff", 0)
    fun notifyOn(c: Context): Int = p(c).getInt("notifyOn", 0)
    fun setNotify(c: Context, off: Int = notifyOff(c), on: Int = notifyOn(c)) {
        p(c).edit().putInt("notifyOff", off).putInt("notifyOn", on).apply()
    }

    /** Changing the source/queue clears the cache: the old schedule no longer applies. */
    fun set(c: Context, source: Source = source(c), queue: String = queue(c)) {
        if (source == source(c) && queue == queue(c)) return
        p(c).edit().putString("source", source.name).putString("queue", queue).apply()
        Store.clear(c)
    }
}

/** Cache in SharedPreferences: "2026-11-08=0000111...;2026-11-09=..." (48 slots of 30 min). */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("sched", Context.MODE_PRIVATE)

    /**
     * Merges new days into the old ones (new ones win), drops days older than yesterday
     * and the days in [missing]: the source no longer publishes them, so the cached copy is stale.
     */
    fun merge(
        old: Map<LocalDate, IntArray>, fresh: Map<LocalDate, IntArray>, today: LocalDate,
        missing: Collection<LocalDate> = emptyList(),
    ): java.util.SortedMap<LocalDate, IntArray> =
        (old.filterKeys { it !in missing } + fresh)
            .filterKeys { !it.isBefore(today.minusDays(1)) }.toSortedMap()

    fun save(c: Context, fresh: Map<LocalDate, IntArray>, missing: Collection<LocalDate> = emptyList()) {
        val today = LocalDate.now(Schedule.ZONE)
        val merged = merge(raw(c), fresh, today, missing)
        val s = merged.entries.joinToString(";") { (d, f) -> "$d=" + f.joinToString("") }
        p(c).edit().putString("days", s).putLong("fetched", System.currentTimeMillis())
            .remove("error").apply()
    }

    /** Slot states 0/1/2 per day. */
    fun raw(c: Context): Map<LocalDate, IntArray> {
        val s = p(c).getString("days", null) ?: return emptyMap()
        return s.split(";").mapNotNull {
            val parts = it.split("=")
            if (parts.size != 2 || parts[1].length != Schedule.SLOTS) return@mapNotNull null
            runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                ?.let { d -> d to IntArray(Schedule.SLOTS) { i -> parts[1][i].digitToIntOrNull() ?: 0 } }
        }.toMap()
    }

    /** true: no power (including "may be off" while switching). */
    fun load(c: Context, maybeIsOff: Boolean = true): Map<LocalDate, BooleanArray> =
        raw(c).mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 1 || (maybeIsOff && a[it] == 2) } }

    /** true: switching slot, power may be off. */
    fun loadMaybe(c: Context): Map<LocalDate, BooleanArray> =
        raw(c).mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 2 } }

    /** What the user sees: schedule + error. The fetch time is deliberately left out. */
    fun snapshot(c: Context): String = (p(c).getString("days", "") ?: "") + "|" + (error(c) ?: "")

    fun clear(c: Context) { p(c).edit().clear().apply() }

    fun fetchedAt(c: Context): Long = p(c).getLong("fetched", 0L)
    /** Data older than [STALE_WARN_MS]: show a warning, the schedule may have changed. */
    fun isStale(c: Context, now: Long = System.currentTimeMillis()): Boolean =
        fetchedAt(c).let { it > 0 && now - it > STALE_WARN_MS }

    const val STALE_WARN_MS = 3 * 60 * 60_000L

    fun error(c: Context): String? = p(c).getString("error", null)
    fun setError(c: Context, msg: String) { p(c).edit().putString("error", msg).apply() }
}

object Fetcher {
    /** Blocking call; true on success. The error is written to Store.error. */
    fun refresh(c: Context): Boolean = try { fetch(c) } catch (e: java.io.IOException) {
        Store.setError(c, "Немає з'єднання")
        false
    } catch (e: Exception) {
        Store.setError(c, e.message ?: e.toString())
        false
    }

    private fun fetch(c: Context): Boolean {
        val source = Settings.source(c)
        val queue = Settings.queue(c)
        val today = LocalDate.now(Schedule.ZONE)
        val hour = java.time.LocalTime.now(Schedule.ZONE).hour
        val days = source.load(queue, today, hour)
        if (days.isEmpty()) error("Графік черги $queue не знайдено")
        // settings changed while the request was in flight: the result is stale
        if (source != Settings.source(c) || queue != Settings.queue(c)) return false
        // Tomorrow was asked for but is gone (the source withdrew it): don't keep showing the old copy.
        // Today is kept: a missing today is more likely a glitch of the site than a real change.
        val missing = source.requested(today, hour).filter { it.isAfter(today) && it !in days }
        Store.save(c, days, missing)
        return true
    }

    /** Refresh the cache and redraw the tile + complication. */
    /**
     * Refresh the cache and redraw the tile + complication, but only if the schedule or the error changed.
     * POE usually returns the same schedule, and there is no need to wake the watch face for nothing
     * (the tile's "updated HH:MM" catches up on its regular 15-minute refresh).
     */
    fun refreshAndNotify(c: Context): Boolean {
        val before = Store.snapshot(c)
        val ok = refresh(c)
        if (Store.snapshot(c) != before) notifyAll(c)
        Notify.schedule(c)
        return ok
    }

    fun notifyAll(c: Context) {
        TileService.getUpdater(c).requestUpdate(LightTileService::class.java)
        LightComplicationService.requestUpdate(c)
        Notify.schedule(c)
    }
}

class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val ok = withContext(Dispatchers.IO) { Fetcher.refreshAndNotify(applicationContext) }
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}

object Refresh {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private const val STALE_MS = 15 * 60_000L

    /** Periodic refresh every 15 min (the WorkManager minimum). POE can change the schedule at any time. */
    fun ensure(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork("periodic") // old 30-minute job
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "periodic15", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        )
    }

    /** One-off refresh now. */
    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "once", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
        )
    }

    /** ensure() + a one-off refresh if the cache is stale. */
    fun ifStale(c: Context) {
        ensure(c)
        if (System.currentTimeMillis() - Store.fetchedAt(c) > STALE_MS) now(c)
    }
}
