package com.yehor.lightwatch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** What a [DialView] shows. [flags] and [maybe] are the 48 half-hour slots of one day; null means no data. */
class DialData(
    val flags: BooleanArray?,
    val maybe: BooleanArray?,
    /** The current slot of today; slots before it are drawn dark. -1 for another day. */
    val curSlot: Int,
    /** Minute of the day for the "now" mark and the highlighted hour; -1 for another day. */
    val nowMinute: Int,
    /** A yellow slot is drawn amber (it means an outage) or green (it means power is on). */
    val yellowAsOutage: Boolean,
    val date: String,
    val title: String,
    val summary: String,
    val summaryColor: Int,
    /** 1 = one ring of 24 hours, 2 = two rings of 12 hours each. */
    val rings: Int = 1,
    /** With two rings: also label the hours 12-24 inside the inner ring. */
    val innerLabels: Boolean = true,
)

/**
 * A 24-hour ring for one day: 48 short dashes (one per half hour, 00:00 at the top), the hours around it,
 * a white mark at the current time and, in the middle, the day, its date and the total outage time.
 * It is as wide as it is tall and is meant to fill the round screen.
 */
@SuppressLint("ViewConstructor") // only ever created from code, with its data
class DialView(c: Context, private var data: DialData) : View(c) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.WHITE
        strokeWidth = dp(3f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    /** Moves the "now" mark and the highlighted hour (called every minute). */
    fun setNow(minute: Int) {
        if (minute == data.nowMinute) return
        data = DialData(
            data.flags, data.maybe, data.curSlot, minute, data.yellowAsOutage,
            data.date, data.title, data.summary, data.summaryColor, data.rings, data.innerLabels
        )
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private fun color(role: DialModel.Role, past: Boolean): Int = when (role) {
        DialModel.Role.RED -> if (past) Palette.RED_DIM else Palette.RED
        DialModel.Role.AMBER -> if (past) Palette.AMBER_DIM else Palette.AMBER
        DialModel.Role.GREEN -> if (past) Palette.GREEN_DIM else Palette.GREEN
        DialModel.Role.NO_DATA -> Palette.NODATA
    }

    override fun onDraw(cv: Canvas) {
        val size = width.toFloat()
        val cx = size / 2
        val cy = size / 2
        val rings = data.rings
        val two = rings == 2

        val labelSize = sp(11f)
        val labelR = cx - dp(4f) - labelSize / 2
        val thickness = dp(if (two) 10f else 11f)
        val ringGap = dp(4f)
        val outer = labelR - labelSize / 2 - dp(5f)
        // radius of the middle of each ring, outermost first
        val ringR = FloatArray(rings) { outer - thickness / 2 - it * (thickness + ringGap) }
        dash.strokeWidth = dp(if (two) 5f else 5.5f)

        // one dash per slot with a small gap; the round caps are taken out of the visible length so that
        // the dash is a pill and not longer than its slot
        val slotDeg = DialModel.slotDegrees(rings)
        val gapDeg = if (two) 2.6f else 1.3f
        for (i in 0 until Schedule.SLOTS) {
            val r = ringR[DialModel.ringOfSlot(i, rings)]
            val capDeg = Math.toDegrees((dash.strokeWidth / 2 / r).toDouble()).toFloat()
            val sweep = max(0.3f, slotDeg - gapDeg - 2 * capDeg)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            val role = DialModel.roleOf(data.flags, data.maybe, i, data.yellowAsOutage)
            dash.color = color(role, DialModel.isPast(i, data.curSlot))
            cv.drawArc(oval, -90f + DialModel.slotAngle(i, rings) + gapDeg / 2 + capDeg, sweep, false, dash)
        }

        // hours around the ring, upright; the current hour is highlighted
        val hour = if (data.nowMinute >= 0) DialModel.hourOf(data.nowMinute) else -1
        fun label(shown: Int, radius: Float, hot: Boolean, position: Int = shown) {
            val a = Math.toRadians(if (two) (position % 12) * 30.0 else position * 15.0)
            val x = cx + radius * sin(a).toFloat()
            val y = cy - radius * cos(a).toFloat() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
            text.color = if (hot) Palette.WHITE else Palette.LABEL
            text.typeface = if (hot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cv.drawText("%02d".format(shown), x, y, text)
        }
        text.textSize = labelSize
        val hotOuter = if (hour >= 0) DialModel.hotLabel(hour, rings, data.innerLabels) else -1
        // like the tile: with two rings and nothing inside, the outer labels show 12-23 from noon on
        val pm = two && !data.innerLabels && data.nowMinute >= 720
        for (h in 0 until (if (two) 12 else 24)) label(if (pm) h + 12 else h, labelR, h == hotOuter, h)
        var freeR = ringR[rings - 1] - thickness / 2 - dp(3f)
        if (two && data.innerLabels) {
            val innerSize = sp(9f)
            text.textSize = innerSize
            val r = freeR - innerSize / 2 - dp(1f)
            for (h in 12 until 24) label(h, r, h == hour)
            freeR = r - innerSize / 2 - dp(2f)
        }

        // the current time: a white stroke across its ring
        if (data.nowMinute >= 0) {
            val a = Math.toRadians(DialModel.angleOfMinute(data.nowMinute, rings).toDouble())
            val mid = ringR[DialModel.ringOfMinute(data.nowMinute, rings)]
            val r1 = mid - thickness / 2 - dp(3f)
            val r2 = mid + thickness / 2 + dp(3f)
            cv.drawLine(
                cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(),
                cx + r2 * sin(a).toFloat(), cy - r2 * cos(a).toFloat(), mark
            )
        }

        // the middle: which day this is, its date and the total; smaller when the rings leave less room
        val oneRingFree = outer - thickness - dp(3f)
        val k = if (two) (freeR / oneRingFree).coerceIn(0.6f, 1f) else 1f
        val titleSize = sp(24f) * k
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = titleSize
        text.color = Palette.WHITE
        val titleBase = cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
        cv.drawText(data.title, cx, titleBase, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = sp(12f) * k
        text.color = Palette.LABEL
        cv.drawText(data.date, cx, titleBase - titleSize * 1.05f, text)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = sp(16f) * k
        text.color = data.summaryColor
        cv.drawText(data.summary, cx, titleBase + titleSize * 1.05f, text)
    }
}
