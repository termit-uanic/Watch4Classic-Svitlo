package app.wattlo.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.wattlo.DialModel
import app.wattlo.Palette
import app.wattlo.Schedule
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Which hour numbers a widget ring shows around it. */
enum class RingLabels { NONE, CURRENT, OUTER, BOTH }

/** What the ring says in the middle: the date, which day it is (empty: not said) and the total outage time (like the app's dial). */
class RingCenter(val date: String, val title: String, val summary: String, val summaryColor: Int)

/**
 * The day ring for the widgets, drawn into a bitmap (a widget can only show views a RemoteViews supports): the same
 * dashes and colours as [app.wattlo.DialView], but without the hours and the text, so that it stays clear
 * at a small size. Everything is a fraction of the size, the bitmap is scaled by the ImageView.
 */
object WidgetRing {
    fun render(
        flags: BooleanArray?, maybe: BooleanArray?, curSlot: Int, nowMinute: Int,
        rings: Int, yellowAsOutage: Boolean, shownLabels: RingLabels = RingLabels.NONE, px: Int = 360, center: RingCenter? = null,
    ): Bitmap {
        val labels = shownLabels
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val size = px.toFloat()
        val c = size / 2
        val two = rings == 2
        // the band of a ring; the dashes are half as wide as the band, like on the app's dial
        // (with words in the middle the bands are a little thinner, which leaves the words more room)
        val thickness = size * if (two) (if (center != null) 0.064f else 0.085f) else 0.08f
        val gap = size * if (center != null && two) 0.014f else 0.02f
        // with hour numbers the rings move inwards to leave them a band
        val labelSize = size * 0.062f
        val labelBand = if (labels == RingLabels.NONE) 0f else labelSize + size * 0.02f
        val outerMid = c - size * 0.04f - labelBand - thickness / 2
        val ringR = FloatArray(rings) { outerMid - it * (thickness + gap) }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = thickness / 2
        }
        val oval = RectF()
        val slotDeg = DialModel.slotDegrees(rings)
        val gapDeg = if (two) 3f else 1.6f
        for (i in 0 until Schedule.SLOTS) {
            val r = ringR[DialModel.ringOfSlot(i, rings)]
            // the round caps are taken out of the visible length: a dash is a pill that fits its slot
            val capDeg = Math.toDegrees((thickness / 4 / r).toDouble()).toFloat()
            val sweep = max(0.3f, slotDeg - gapDeg - 2 * capDeg)
            oval.set(c - r, c - r, c + r, c + r)
            paint.color = color(DialModel.roleOf(flags, maybe, i, yellowAsOutage), DialModel.isPast(i, curSlot))
            cv.drawArc(oval, -90f + DialModel.slotAngle(i, rings) + gapDeg / 2 + capDeg, sweep, false, paint)
        }

        if (nowMinute >= 0) {
            val a = Math.toRadians(DialModel.angleOfMinute(nowMinute, rings).toDouble())
            val mid = ringR[DialModel.ringOfMinute(nowMinute, rings)]
            val r1 = mid - thickness / 2 - size * 0.015f
            val r2 = mid + thickness / 2 + size * 0.015f
            paint.color = Palette.WHITE
            paint.strokeWidth = size * 0.012f + 2f
            cv.drawLine(
                c + r1 * sin(a).toFloat(), c - r1 * cos(a).toFloat(),
                c + r2 * sin(a).toFloat(), c - r2 * cos(a).toFloat(), paint
            )
        }
        if (labels != RingLabels.NONE) drawLabels(cv, size, two, ringR, thickness, labelSize, labels, nowMinute)
        if (center != null) {
            // the free disc inside the innermost thing: the inner hour numbers (two rings, all hours) or the innermost ring
            val inner = if (two && labels == RingLabels.BOTH) ringR.last() - thickness / 2 - labelSize * 0.8f - size * 0.01f
            else ringR.last() - thickness / 2 - size * 0.015f
            drawCenter(cv, c, inner - size * (if (two && labels == RingLabels.BOTH) 0.03f else 0.01f), size, center)
        }
        return bmp
    }

    /**
     * Three centred lines. Each line is scaled to be narrower than the chord of the disc at its height (the date is
     * short and keeps its size, the long word shrinks alone); the date goes when it would be too small to read.
     */
    private fun drawCenter(cv: Canvas, c: Float, freeR: Float, size: Float, ctr: RingCenter) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        for (withDate in listOf(true, false)) {
            val lines = buildList {
                if (withDate) add(Triple(ctr.date, size * 0.07f, Palette.LABEL))
                if (ctr.title.isNotEmpty()) add(Triple(ctr.title, size * 0.115f, Palette.WHITE))
                add(Triple(ctr.summary, size * 0.088f, ctr.summaryColor))
            }
            val sizes = lines.map { it.second }.toFloatArray()
            // width per unit of size; never narrower than a typical word ("Tomorrow", "-11h 30m"), so that today and
            // tomorrow get the same sizes and the same lines
            val floor = buildList { if (withDate) add(3.0f); if (ctr.title.isNotEmpty()) add(4.4f); add(4.2f) }
            val unit = lines.mapIndexed { i, (t, base, _) -> text.textSize = base; maxOf(text.measureText(t) / base, floor[i]) }
            repeat(5) { // the heights decide the chords, the chords decide the sizes
                val heights = sizes.map { it * 1.18f }
                var top = -heights.sum() / 2
                for (i in lines.indices) {
                    val far = maxOf(kotlin.math.abs(top), kotlin.math.abs(top + heights[i]))
                    val chord = 2 * kotlin.math.sqrt(max(0f, freeR * freeR - far * far)) * 0.92f
                    sizes[i] = minOf(lines[i].second, chord / unit[i])
                    top += heights[i]
                }
            }
            if (withDate && sizes[0] < size * 0.028f) continue
            val heights = sizes.map { it * 1.18f }
            var y = c - heights.sum() / 2
            for ((i, line) in lines.withIndex()) {
                text.textSize = sizes[i]
                text.color = line.third
                text.typeface = if (withDate && i == 0) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                y += heights[i]
                cv.drawText(line.first, c, y - heights[i] * 0.2f, text)
            }
            return
        }
    }

    /**
     * The hours around the ring, upright; the current hour is white and bold. With two rings the outer numbers are
     * 00-11 (and 12-23 from noon on when only the outer ones are shown) and, with [RingLabels.BOTH], 12-23 sit inside.
     */
    private fun drawLabels(
        cv: Canvas, size: Float, two: Boolean, ringR: FloatArray, thickness: Float, labelSize: Float,
        labels: RingLabels, nowMinute: Int,
    ) {
        val c = size / 2
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val hour = if (nowMinute >= 0) nowMinute / 60 else -1
        fun label(shown: Int, radius: Float, hot: Boolean, position: Int, textSize: Float) {
            text.textSize = textSize
            text.color = if (hot) Palette.WHITE else Palette.LABEL
            text.typeface = if (hot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val a = Math.toRadians(if (two) (position % 12) * 30.0 else position * 15.0)
            val x = c + radius * sin(a).toFloat()
            val y = c - radius * cos(a).toFloat() - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
            cv.drawText("%02d".format(shown), x, y, text)
        }
        val labelR = c - size * 0.03f - labelSize / 2
        if (labels == RingLabels.CURRENT) {
            if (hour >= 0) label(hour, labelR, true, hour, labelSize)
            return
        }
        // the outer label that is the current hour (two rings: the afternoon hours share the outer labels or use the inner ones)
        val hotOuter = when {
            hour < 0 -> -1
            !two -> hour
            labels == RingLabels.OUTER -> hour % 12
            hour < 12 -> hour
            else -> -1
        }
        val pm = two && labels == RingLabels.OUTER && nowMinute >= 720
        for (h in 0 until (if (two) 12 else 24)) label(if (pm) h + 12 else h, labelR, h == hotOuter, h, labelSize)
        if (two && labels == RingLabels.BOTH) {
            val innerSize = labelSize * 0.8f
            val r = ringR.last() - thickness / 2 - innerSize / 2 - size * 0.01f
            for (h in 12 until 24) label(h, r, h == hour, h, innerSize)
        }
    }

    private fun color(role: DialModel.Role, past: Boolean): Int = when (role) {
        DialModel.Role.RED -> if (past) Palette.RED_DIM else Palette.RED
        DialModel.Role.AMBER -> if (past) Palette.AMBER_DIM else Palette.AMBER
        DialModel.Role.GREEN -> if (past) Palette.GREEN_DIM else Palette.GREEN
        DialModel.Role.NO_DATA -> Palette.NODATA
    }
}
