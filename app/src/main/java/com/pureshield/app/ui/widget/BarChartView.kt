package com.pureshield.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.pureshield.app.R
import com.pureshield.app.util.BnDigits

/**
 * Daily-focus bar chart (Statistics screen): vertical gradient bars
 * (accent1 -> accent2), a light track behind each bar, x-axis labels below,
 * optional y-axis labels on the left, an optional dashed horizontal reference
 * line, and optional value labels above the bars.
 *
 * Bars come only from real [FocusDay] rows; days with no data draw as empty
 * tracks. Values are formatted with [BnDigits] (Bengali digits).
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Bar(val label: String, val valueMs: Long)

    private var bars: List<Bar> = emptyList()
    /** Draw every nth x-axis label. */
    var labelStep: Int = 1
    /** False for crowded charts (30 daily bars): the bar heights speak for themselves. */
    var showValues: Boolean = true
    /** Y-axis labels, evenly spaced from 0 (bottom) to the max (top), e.g. 0m/30m/60m. */
    var yLabels: List<String> = emptyList()
    /** Fraction of the chart height (0..1) where the dashed line is drawn, or null. */
    var dashedLineFraction: Float? = null
    /** Formats the value label drawn above a bar; defaults to a compact Bengali duration. */
    var valueFormatter: ((Long) -> String)? = null

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val yTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val rect = RectF()

    fun setBars(bars: List<Bar>) {
        this.bars = bars
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (180 * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val track = MaterialColors.getColor(this, R.attr.cardBackground2, 0xFFE8EAF2.toInt())
        val secondary = MaterialColors.getColor(this, R.attr.subColor, 0xFF8A8FA8.toInt())
        val barTop = MaterialColors.getColor(this, R.attr.accent1, 0xFF3CB68F.toInt())
        val barBottom = MaterialColors.getColor(this, R.attr.accent2, 0xFF1F7A5F.toInt())

        val labelH = 22 * density
        val valueH = if (showValues) 18 * density else 6 * density
        val chartTop = valueH
        val chartBottom = height - labelH
        val chartH = chartBottom - chartTop
        // Room for the y-axis labels on the left.
        val leftPad = if (yLabels.isNotEmpty()) 34 * density else 0f
        val chartW = width - leftPad
        val slot = chartW / bars.size
        val barW = slot * 0.5f
        val max = (bars.maxOf { it.valueMs }).coerceAtLeast(1L)

        barPaint.shader = LinearGradient(
            0f, chartTop, 0f, chartBottom, barTop, barBottom, Shader.TileMode.CLAMP
        )
        trackPaint.color = track
        textPaint.textSize = 11 * density
        yTextPaint.textSize = 11 * density
        yTextPaint.color = secondary
        dashedPaint.color = secondary
        dashedPaint.strokeWidth = 1.5f * density

        // Dashed horizontal reference line.
        dashedLineFraction?.let { fraction ->
            val y = chartBottom - chartH * fraction.coerceIn(0f, 1f)
            canvas.drawLine(leftPad, y, width.toFloat(), y, dashedPaint)
        }

        // Y-axis labels, evenly spaced 0..max.
        if (yLabels.size > 1) {
            val step = chartH / (yLabels.size - 1)
            yLabels.forEachIndexed { i, label ->
                val y = chartBottom - step * i + 4 * density
                canvas.drawText(label, leftPad - 6 * density, y, yTextPaint)
            }
        }

        bars.forEachIndexed { i, bar ->
            val cx = leftPad + slot * i + slot / 2f
            rect.set(cx - barW / 2, chartTop, cx + barW / 2, chartBottom)
            canvas.drawRoundRect(rect, 6 * density, 6 * density, trackPaint)

            if (bar.valueMs > 0) {
                val h = (chartH * bar.valueMs / max).coerceAtLeast(6 * density)
                rect.set(cx - barW / 2, chartBottom - h, cx + barW / 2, chartBottom)
                canvas.drawRoundRect(rect, 6 * density, 6 * density, barPaint)
                if (showValues) {
                    textPaint.color = secondary
                    val text = valueFormatter?.invoke(bar.valueMs) ?: defaultValueText(bar.valueMs)
                    canvas.drawText(text, cx, chartBottom - h - 4 * density, textPaint)
                }
            }
            if (i % labelStep == 0 && bar.label.isNotEmpty()) {
                textPaint.color = secondary
                canvas.drawText(bar.label, cx, height - 5 * density, textPaint)
            }
        }
        barPaint.shader = null
    }

    private fun defaultValueText(valueMs: Long): String {
        val minutes = (valueMs / 60_000L).toInt()
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) {
            BnDigits.toBnDigits(hours.toString()) + "ঘ " +
                BnDigits.toBnDigits(mins.toString()) + "মি"
        } else {
            BnDigits.toBnDigits(mins.toString()) + " মি"
        }
    }
}
