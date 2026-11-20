package com.pureshield.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.pureshield.app.R
import com.pureshield.app.ui.attrBoolean
import com.pureshield.app.ui.attrColor
import kotlin.math.PI
import kotlin.math.cos

/**
 * The dashboard protection ring (demo: 218x218px, r=96, stroke-width 11,
 * gradient accent1 -> accent2 with round cap and glow, two breathing halo
 * discs).
 *
 * Demo semantics: the ring shows REMAINING time — full at idle and at lock
 * start, depleting to empty as the lock runs out. [progress] is 0..1 ELAPSED
 * toward the time unlock, so the drawn arc is 360 * (1 - progress).
 *
 * Halos (demo `.halo` + `.halo.h2`): two radial-gradient discs
 * (mint at ~20% alpha fading to transparent at 68%), breathing 5s
 * (scale .92 -> 1.06, opacity .55 -> .95), the second layer half a cycle
 * behind the first at 60% opacity. Themes that hide halos in the demo
 * (Original, Zen) set showHalo=false and draw none.
 * Colours come from the theme (?attr/accent1, ?attr/accent2, ?attr/lineColor,
 * ?attr/mintColor).
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0f..1f elapsed toward the time unlock (0 = just started, 1 = done). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** 0f..1f breathing phase, driven by [breathAnimator] (5s per cycle). */
    private var breathPhase: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val strokeWidth = 11f * resources.displayMetrics.density
    private val accent1 = context.attrColor(R.attr.accent1)
    private val accent2 = context.attrColor(R.attr.accent2)
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
        style = Paint.Style.FILL
    }

    private val arcRect = RectF()
    private var ringRadius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var haloBaseRadius = 0f
    private var haloGradient: RadialGradient? = null
    private var breathAnimator: ValueAnimator? = null

    init {
        // Soft glow on the progress arc.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        progressPaint.setShadowLayer(
            14f * resources.displayMetrics.density, 0f, 0f, accent2
        )
        if (showHalo) {
            breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 5000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { breathPhase = it.animatedValue as Float }
                start()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        // Demo r=96 on a 218 box.
        ringRadius = minOf(w, h) * 96f / 218f
        arcRect.set(
            centerX - ringRadius, centerY - ringRadius,
            centerX + ringRadius, centerY + ringRadius
        )
        // Halos stay inside the box even at their 1.06x peak.
        haloBaseRadius = minOf(w, h) / 2f - 9f * resources.displayMetrics.density
        haloGradient = RadialGradient(
            centerX, centerY, haloBaseRadius,
            intArrayOf(ColorUtils.setAlphaComponent(mintColor, 51), Color.TRANSPARENT),
            floatArrayOf(0f, 0.68f),
            Shader.TileMode.CLAMP
        )
        val sweep = SweepGradient(centerX, centerY, intArrayOf(accent1, accent2), null)
        val matrix = Matrix()
        matrix.setRotate(-90f, centerX, centerY)
        sweep.setLocalMatrix(matrix)
        progressPaint.shader = sweep
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ringRadius <= 0f) return

        if (showHalo && haloGradient != null) {
            // Two breathing halo discs: layer 2 is half a cycle behind
            // layer 1 and drawn at 60% opacity (demo .halo.h2).
            haloPaint.shader = haloGradient
            for (layer in 0..1) {
                val phase = (breathPhase + layer * 0.5f) % 1f
                // Demo breathe: 0%/100% scale .92 opacity .55, 50% scale 1.06 opacity .95.
                val scale = 0.99f - 0.07f * cos(2f * PI.toFloat() * phase)
                var alpha01 = 0.75f - 0.20f * cos(2f * PI.toFloat() * phase)
                if (layer == 1) alpha01 *= 0.6f
                haloPaint.alpha = (alpha01 * 255f).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.scale(scale, scale, centerX, centerY)
                canvas.drawCircle(centerX, centerY, haloBaseRadius, haloPaint)
                canvas.restore()
            }
            haloPaint.shader = null
        }

        canvas.drawCircle(centerX, centerY, ringRadius, trackPaint)

        // Remaining time: full ring at idle / lock start, depleting to empty.
        val sweep = 360f * (1f - progress)
        if (sweep > 0.5f) {
            canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        breathAnimator?.cancel()
        breathAnimator = null
        super.onDetachedFromWindow()
    }
}
