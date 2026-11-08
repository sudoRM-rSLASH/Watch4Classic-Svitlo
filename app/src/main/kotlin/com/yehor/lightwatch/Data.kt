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

/** Звідки беремо графік. Стани слотів: 0 — є світло, 1 — немає, 2 — перемикання (може не бути). */
enum class Source(val host: String) {
    POE("poe.pl.ua"),
    BEZSVITLA("bezsvitla.com.ua"),
    ENERGY_UA("energy-ua.info");

    fun load(queue: String, today: LocalDate): Map<LocalDate, IntArray> = when (this) {
        POE -> {
            // newgpv-info.php віддає HTML-таблицю всіх черг на дату; порожньо — графік ще не опубліковано
            val out = LinkedHashMap<LocalDate, IntArray>()
            // графік на завтра POE публікує ввечері — до полудня не смикаємо зайвий запит
            val noon = java.time.LocalTime.now(Schedule.ZONE).hour >= 12
            for (d in if (noon) listOf(today, today.plusDays(1)) else listOf(today)) {
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
    /** GET, або POST form-urlencoded, якщо є [form]. */
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

/** Налаштування: джерело + черга. */
object Settings {
    val QUEUES = (1..6).flatMap { q -> (1..2).map { "$q.$it" } }

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun source(c: Context): Source =
        runCatching { Source.valueOf(p(c).getString("source", null)!!) }.getOrDefault(Source.POE)

    fun queue(c: Context): String = p(c).getString("queue", null)?.takeIf { it in QUEUES } ?: "3.2"

    /** Зміна джерела/черги скидає кеш — старий графік вже не про нас. */
    fun set(c: Context, source: Source = source(c), queue: String = queue(c)) {
        if (source == source(c) && queue == queue(c)) return
        p(c).edit().putString("source", source.name).putString("queue", queue).apply()
        Store.clear(c)
    }
}

/** Кеш у SharedPreferences: "2026-11-08=0000111...;2026-11-09=..." (48 слотів по 30 хв). */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("sched", Context.MODE_PRIVATE)

    /** Зливає нові дні зі старими (нові мають пріоритет), викидає дні старші за вчора. */
    fun save(c: Context, fresh: Map<LocalDate, IntArray>) {
        val today = LocalDate.now(Schedule.ZONE)
        val merged = (raw(c) + fresh).filterKeys { !it.isBefore(today.minusDays(1)) }.toSortedMap()
        val s = merged.entries.joinToString(";") { (d, f) -> "$d=" + f.joinToString("") }
        p(c).edit().putString("days", s).putLong("fetched", System.currentTimeMillis())
            .remove("error").apply()
    }

    /** Стани слотів 0/1/2 по днях. */
    fun raw(c: Context): Map<LocalDate, IntArray> {
        val s = p(c).getString("days", null) ?: return emptyMap()
        return s.split(";").mapNotNull {
            val parts = it.split("=")
            if (parts.size != 2 || parts[1].length != Schedule.SLOTS) return@mapNotNull null
            runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                ?.let { d -> d to IntArray(Schedule.SLOTS) { i -> parts[1][i].digitToIntOrNull() ?: 0 } }
        }.toMap()
    }

    /** true — світла немає (у т.ч. "може не бути" під час перемикання). */
    fun load(c: Context): Map<LocalDate, BooleanArray> =
        raw(c).mapValues { (_, a) -> BooleanArray(a.size) { a[it] != 0 } }

    /** true — слот перемикання, світла може не бути. */
    fun loadMaybe(c: Context): Map<LocalDate, BooleanArray> =
        raw(c).mapValues { (_, a) -> BooleanArray(a.size) { a[it] == 2 } }

    /** Що бачить користувач: графік + помилка. Час оновлення сюди навмисно не входить. */
    fun snapshot(c: Context): String = (p(c).getString("days", "") ?: "") + "|" + (error(c) ?: "")

    fun clear(c: Context) { p(c).edit().clear().apply() }

    fun fetchedAt(c: Context): Long = p(c).getLong("fetched", 0L)
    fun error(c: Context): String? = p(c).getString("error", null)
    fun setError(c: Context, msg: String) { p(c).edit().putString("error", msg).apply() }
}

object Fetcher {
    /** Блокуючий виклик; true — успіх. Помилка пишеться в Store.error. */
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
        val days = source.load(queue, LocalDate.now(Schedule.ZONE))
        if (days.isEmpty()) error("Графік черги $queue не знайдено")
        // налаштування змінили, поки йшов запит — результат вже неактуальний
        if (source != Settings.source(c) || queue != Settings.queue(c)) return false
        Store.save(c, days)
        return true
    }

    /** Оновити кеш і перемалювати тайл + complication. */
    /**
     * Оновити кеш і перемалювати тайл + complication — лише якщо графік або помилка змінились.
     * Зазвичай POE віддає той самий графік, і будити циферблат дарма не треба
     * ("оновлено HH:MM" на тайлі підтягнеться при його плановому перезапиті раз на 15 хв).
     */
    fun refreshAndNotify(c: Context): Boolean {
        val before = Store.snapshot(c)
        val ok = refresh(c)
        if (Store.snapshot(c) != before) notifyAll(c)
        return ok
    }

    fun notifyAll(c: Context) {
        TileService.getUpdater(c).requestUpdate(LightTileService::class.java)
        LightComplicationService.requestUpdate(c)
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

    /** Періодичне оновлення раз на 15 хв (мінімум WorkManager). POE може змінити графік будь-коли. */
    fun ensure(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork("periodic") // стара 30-хвилинна задача
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "periodic15", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        )
    }

    /** Разове оновлення зараз. */
    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "once", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
        )
    }

    /** ensure() + разове оновлення, якщо кеш застарів. */
    fun ifStale(c: Context) {
        ensure(c)
        if (System.currentTimeMillis() - Store.fetchedAt(c) > STALE_MS) now(c)
    }
}
