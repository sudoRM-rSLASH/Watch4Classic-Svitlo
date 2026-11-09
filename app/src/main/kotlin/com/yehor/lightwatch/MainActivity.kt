package com.yehor.lightwatch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * App screen (scrollable, including with the bezel):
 *  1) status and countdown,
 *  2) outages for today / tomorrow,
 *  3) last update time, "Оновити" (refresh) button,
 *  4) settings: source, queue, yellow slots, notifications.
 */
class MainActivity : Activity() {
    private lateinit var head: LinearLayout
    private lateinit var lists: LinearLayout
    private lateinit var footer: TextView
    private lateinit var btn: TextView
    private lateinit var sourceRow: TextView
    private lateinit var queueRow: TextView
    private lateinit var maybeRow: TextView
    private lateinit var maybeSwitch: Switch
    private lateinit var maybeCard: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Refresh.ensure(this)
        window.decorView.setBackgroundColor(Palette.BG)

        val w = resources.displayMetrics.widthPixels
        val side = (w * 0.15f).toInt()

        head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = (w * 0.62f).toInt()
        }
        lists = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footer = label(11f, Palette.LABEL).apply { gravity = Gravity.CENTER }
        btn = pill("Оновити", Palette.GREEN, Palette.BG).apply { setOnClickListener { refreshNow() } }
        sourceRow = settingRow { pickSource() }
        queueRow = settingRow { pickQueue() }
        maybeRow = label(13f, Palette.GREY)
        maybeSwitch = Switch(this).apply {
            isClickable = false // the whole row handles the click
            isFocusable = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Palette.AMBER, Palette.GREY))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(Palette.AMBER_DIM, Palette.LABEL))
        }
        maybeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(Palette.CARD)
            }
            addView(maybeRow, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(maybeSwitch)
            setOnClickListener {
                Settings.setMaybeIsOff(this@MainActivity, !Settings.maybeIsOff(this@MainActivity))
                Fetcher.notifyAll(this@MainActivity)
                render()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (w * 0.12f).toInt(), side, (w * 0.22f).toInt())
            addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(lists)
            addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(14f).toInt()
            })
            addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8f).toInt()
            })
            addView(label(13f, Palette.WHITE, bold = true).apply {
                text = "Налаштування"
                setPadding(0, dp(18f).toInt(), 0, dp(4f).toInt())
            })
            addView(sourceRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6f).toInt()
            })
            addView(queueRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6f).toInt()
            })
            addView(maybeCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(col)
        }
        setContentView(scroll)
        scroll.requestFocus() // rotating the bezel scrolls the screen

        if (System.currentTimeMillis() - Store.fetchedAt(this) > 2 * 60_000L) refreshNow()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    // ---------------------------------------------------------------- actions

    private fun refreshNow() {
        if (busy) return
        busy = true
        btn.text = "Оновлення…"
        btn.alpha = 0.6f
        thread {
            Fetcher.refreshAndNotify(applicationContext)
            runOnUiThread {
                busy = false
                btn.text = "Оновити"
                btn.alpha = 1f
                render()
            }
        }
    }

    private fun pickSource() {
        val all = Source.entries
        AlertDialog.Builder(this)
            .setTitle("Джерело")
            .setSingleChoiceItems(all.map { it.host }.toTypedArray(), all.indexOf(Settings.source(this))) { d, i ->
                d.dismiss()
                applySettings { Settings.set(this, source = all[i]) }
            }
            .show()
    }

    private fun pickQueue() {
        val all = Settings.QUEUES
        AlertDialog.Builder(this)
            .setTitle("Черга")
            .setSingleChoiceItems(all.map { "Черга $it" }.toTypedArray(), all.indexOf(Settings.queue(this))) { d, i ->
                d.dismiss()
                applySettings { Settings.set(this, queue = all[i]) }
            }
            .show()
    }

    private fun applySettings(change: () -> Unit) {
        val before = Settings.source(this) to Settings.queue(this)
        change()
        if (before == Settings.source(this) to Settings.queue(this)) return
        Fetcher.notifyAll(this)
        render()
        busy = false
        refreshNow()
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val days = Store.load(this, Settings.maybeIsOff(this))
        val maybe = Store.loadMaybe(this)
        val now = ZonedDateTime.now(Schedule.ZONE)
        renderHead(Schedule.status(days, now), now, Schedule.at(maybe, now) == true)

        lists.removeAllViews()
        val today = now.toLocalDate()
        dayBlock("Сьогодні", days[today], maybe[today], Schedule.slotOf(now))
        dayBlock("Завтра", days[today.plusDays(1)], maybe[today.plusDays(1)], -1)
        if (maybe.values.any { m -> m.any { it } }) {
            lists.addView(label(11f, Palette.LABEL).apply {
                text = "жовте — перемикання, світла може не бути"
                setPadding(0, dp(8f).toInt(), 0, 0)
            })
        }

        val sb = StringBuilder()
        val fetched = Store.fetchedAt(this)
        if (fetched > 0) {
            val t = Instant.ofEpochMilli(fetched).atZone(Schedule.ZONE)
            sb.append("Оновлено ").append(t.format(if (t.toLocalDate() == today) HHMM else DDHHMM))
        } else if (!busy) sb.append("Ще не оновлювалось")
        val err = Store.error(this)
        err?.let { sb.append("\n⚠ ").append(it) }
        footer.text = sb
        footer.setTextColor(if (err != null) Palette.AMBER else Palette.LABEL)

        val src = Settings.source(this)
        sourceRow.text = "Джерело\n${src.host}"
        queueRow.text = "Черга\n${Settings.queue(this)}"
        val maybeOff = Settings.maybeIsOff(this)
        maybeRow.text = (if (maybeOff) "Жовте = OFF" else "Жовте = ON") + "\nу відліку"
        maybeSwitch.isChecked = maybeOff
    }

    private fun renderHead(st: Schedule.Status?, now: ZonedDateTime, switching: Boolean) {
        head.removeAllViews()
        fun add(v: View, top: Float = 0f) = head.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(top).toInt()
        })
        add(label(11f, Palette.LABEL, bold = true).apply {
            text = "ЧЕРГА ${Settings.queue(this@MainActivity)}"
            letterSpacing = 0.1f
        })
        if (st == null) {
            add(label(16f, Palette.GREY, bold = true).apply { text = "Немає даних" }, 18f)
            add(label(12f, Palette.LABEL).apply {
                text = if (busy) "завантажую…" else "натисни «Оновити»"
                gravity = Gravity.CENTER
            }, 4f)
            return
        }
        val color = when { switching -> Palette.AMBER; st.off -> Palette.RED; else -> Palette.GREEN }
        val title = when { switching -> "  ПЕРЕМИКАННЯ  "; st.off -> "  СВІТЛА НЕМАЄ  "; else -> "  СВІТЛО Є  " }
        add(pill(title, color, Palette.BG, 12f).apply {
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
        }, 10f)
        add(label(34f, Palette.WHITE, bold = true).apply {
            text = (if (st.changeIsMinimum) "≥ " else "") + Schedule.fmt(st.remaining(now))
        }, 4f)
        val at = hhmm(st.changeAt, now)
        add(label(14f, Palette.GREY).apply {
            text = when {
                st.changeIsMinimum -> "без змін до $at"
                st.off -> "увімкнуть о $at"
                else -> "вимкнуть о $at"
            }
        })
        st.nextLen?.let {
            val what = if (st.off) "зі світлом" else "без світла"
            add(label(12f, Palette.LABEL).apply {
                text = "далі ${if (st.nextEndIsMinimum) "≥ " else ""}${Schedule.fmt(it)} $what"
            }, 2f)
        }
    }

    private fun dayBlock(title: String, flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f).toInt(), 0, dp(6f).toInt())
        }
        row.addView(label(14f, Palette.WHITE, bold = true).apply { text = title; maxLines = 1 },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(label(12f, Palette.LABEL).apply {
            text = when {
                flags == null -> ""
                flags.none { it } -> "без відключень"
                else -> "−${Schedule.fmt(Schedule.offDuration(flags))}"
            }
            if (flags != null && flags.any { it }) setTextColor(Palette.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6f).toInt() })
        lists.addView(row)

        if (flags == null) {
            lists.addView(label(13f, Palette.LABEL).apply { text = "Графіка ще немає" })
            return
        }
        lists.addView(StripView(this, flags, maybe, curSlot), LinearLayout.LayoutParams(MATCH_PARENT, dp(10f).toInt()).apply {
            bottomMargin = dp(6f).toInt()
        })
        val ranges = Schedule.offRanges(flags)
        if (ranges.isEmpty()) lists.addView(label(13f, Palette.GREEN).apply { text = "Світло весь день" })
        for ((a, b) in ranges) {
            val past = curSlot >= 0 && b <= curSlot
            val active = curSlot in a until b
            lists.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3f).toInt(), 0, dp(3f).toInt())
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (past) Palette.RED_DIM else Palette.RED)
                    }
                }, LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply { rightMargin = dp(8f).toInt() })
                addView(label(14f, if (past) Palette.LABEL else Palette.GREY, bold = active).apply {
                    text = "${Schedule.slotTime(a)} – ${Schedule.slotTime(b)}"
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(label(12f, if (active) Palette.RED else Palette.LABEL).apply {
                    text = if (active) "зараз" else Schedule.fmt(java.time.Duration.ofMinutes((b - a) * 30L))
                })
            })
        }
    }

    // ---------------------------------------------------------------- widgets

    private fun settingRow(onClick: () -> Unit) = label(13f, Palette.GREY).apply {
        setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(Palette.CARD)
        }
        setOnClickListener { onClick() }
    }

    private fun pill(s: String, bg: Int, fg: Int, size: Float = 15f) = label(size, fg, bold = true).apply {
        text = s
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(24f)
            setColor(bg)
        }
        setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun hhmm(t: ZonedDateTime, now: ZonedDateTime): String =
        if (t.hour == 0 && t.minute == 0 && t.toLocalDate() != now.toLocalDate()) "24:00" else t.format(HHMM)

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DDHHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    }
}

/** Horizontal strip of the day (48 slots of 30 min). */
private class StripView(c: Context, val flags: BooleanArray, val maybe: BooleanArray?, val curSlot: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val n = flags.size
        val gap = resources.displayMetrics.density * 0.75f
        val cw = (width - gap * (n - 1)) / n
        val r = cw / 2
        for (i in 0 until n) {
            val past = curSlot >= 0 && i < curSlot
            val m = maybe?.getOrNull(i) == true
            p.color = when {
                m && past -> Palette.AMBER_DIM
                m -> Palette.AMBER
                flags[i] && past -> Palette.RED_DIM
                flags[i] -> Palette.RED
                past -> Palette.GREEN_DIM
                else -> Palette.GREEN
            }
            val x = i * (cw + gap)
            val top = if (i == curSlot) 0f else height * 0.2f
            val bottom = if (i == curSlot) height.toFloat() else height * 0.8f
            cv.drawRoundRect(x, top, x + cw, bottom, r, r, p)
        }
    }
}
