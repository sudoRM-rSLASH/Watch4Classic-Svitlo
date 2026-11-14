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
            data.date, data.title, data.summary, data.summaryColor
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

        val labelSize = sp(11f)
        val labelR = cx - dp(4f) - labelSize / 2
        val thickness = dp(11f)
        val outer = labelR - labelSize / 2 - dp(5f)
        val ringR = outer - thickness / 2
        dash.strokeWidth = dp(5.5f)

        // one dash per slot: the slot is 7.5 degrees, a gap of 1.3 degrees is left and the round caps are
        // taken out of the visible length so that the dash is a pill and not longer than its slot
        val capDeg = Math.toDegrees((dash.strokeWidth / 2 / ringR).toDouble()).toFloat()
        val sweep = max(0.3f, 6.2f - 2 * capDeg)
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        for (i in 0 until Schedule.SLOTS) {
            val role = DialModel.roleOf(data.flags, data.maybe, i, data.yellowAsOutage)
            dash.color = color(role, DialModel.isPast(i, data.curSlot))
            cv.drawArc(oval, -90f + i * 7.5f + 0.65f + capDeg, sweep, false, dash)
        }

        // hours around the ring, upright; the current hour is highlighted
        text.textSize = labelSize
        val hot = if (data.nowMinute >= 0) DialModel.hourOf(data.nowMinute) else -1
        val fm = text.fontMetrics
        for (h in 0 until 24) {
            val a = Math.toRadians(h * 15.0)
            val x = cx + labelR * sin(a).toFloat()
            val y = cy - labelR * cos(a).toFloat() - (fm.ascent + fm.descent) / 2
            text.color = if (h == hot) Palette.WHITE else Palette.LABEL
            text.typeface = if (h == hot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cv.drawText("%02d".format(h), x, y, text)
        }

        // the current time: a white stroke across the ring
        if (data.nowMinute >= 0) {
            val a = Math.toRadians(DialModel.angleOfMinute(data.nowMinute).toDouble())
            val r1 = ringR - thickness / 2 - dp(3f)
            val r2 = ringR + thickness / 2 + dp(3f)
            cv.drawLine(
                cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(),
                cx + r2 * sin(a).toFloat(), cy - r2 * cos(a).toFloat(), mark
            )
        }

        // the middle: which day this is, its date and the total
        val titleSize = sp(24f)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = titleSize
        text.color = Palette.WHITE
        val titleBase = cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
        cv.drawText(data.title, cx, titleBase, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = sp(12f)
        text.color = Palette.LABEL
        cv.drawText(data.date, cx, titleBase - titleSize * 1.05f, text)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = sp(16f)
        text.color = data.summaryColor
        cv.drawText(data.summary, cx, titleBase + titleSize * 1.05f, text)
    }
}
