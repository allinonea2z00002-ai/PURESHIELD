package com.pureshield.app.ui.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Keep
import com.pureshield.app.R
import com.pureshield.app.ui.attrColor

/**
 * The dashboard protection ring (demo: 218x218px, stroke-width 11, gradient
 * accent1 -> accent2 with round cap and glow, two breathing halo circles).
 *
 * The ring is drawn slightly smaller than the demo's r=96 so the breathing
 * halos (which grow to 1.06x) stay inside the 218dp box instead of clipping.
 * Colours come from the theme (?attr/ringStart, ?attr/ringEnd, ?attr/lineColor,
 * ?attr/mintColor); [progress] is 0..1 toward the time unlock.
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0f..1f progress toward the time unlock (0 = just started, 1 = done). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Breathing scale for the halo circles, driven by [breathAnimator]. */
    @Keep
    var breathScale: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    private val strokeWidth = 11f * resources.displayMetrics.density
    private val ringStart = context.attrColor(R.attr.ringStart)
    private val ringEnd = context.attrColor(R.attr.ringEnd)
    private val lineColor = context.attrColor(R.attr.lineColor)
    private val mintColor = context.attrColor(R.attr.mintColor)
    private val showHalo = context.attrBoolean(R.attr.showHalo, true)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingView.strokeWidth
        color = lineColor
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val arcRect = RectF()
    private var ringRadius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var breathAnimator: ObjectAnimator? = null

    init {
        // Soft glow on the progress arc.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        progressPaint.setShadowLayer(
            14f * resources.displayMetrics.density, 0f, 0f, ringEnd
        )
        if (showHalo) {
            breathAnimator = ObjectAnimator.ofFloat(this, "breathScale", 0.92f, 1.06f).apply {
                duration = 5000
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        // Leave headroom for the breathing halos outside the ring.
        ringRadius = (minOf(w, h) / 2f) - strokeWidth / 2f - 17f * resources.displayMetrics.density
        arcRect.set(
            centerX - ringRadius, centerY - ringRadius,
            centerX + ringRadius, centerY + ringRadius
        )
        val sweep = SweepGradient(centerX, centerY, intArrayOf(ringStart, ringEnd), null)
        val matrix = Matrix()
        matrix.setRotate(-90f, centerX, centerY)
        sweep.setLocalMatrix(matrix)
        progressPaint.shader = sweep
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ringRadius <= 0f) return

        if (showHalo) {
            // Two breathing halos: one follows the breath, the other breathes
            // in the opposite phase at a smaller amplitude.
            haloPaint.color = mintColor
            haloPaint.alpha = 42
            canvas.drawCircle(centerX, centerY, (ringRadius + 12f * resources.displayMetrics.density) * breathScale, haloPaint)
            haloPaint.alpha = 23
            val inverse = 1f + (1f - breathScale) * 0.5f
            canvas.drawCircle(centerX, centerY, (ringRadius + 20f * resources.displayMetrics.density) * inverse, haloPaint)
        }

        canvas.drawCircle(centerX, centerY, ringRadius, trackPaint)

        if (progress > 0.002f) {
            canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        breathAnimator?.cancel()
        breathAnimator = null
        super.onDetachedFromWindow()
    }
}
