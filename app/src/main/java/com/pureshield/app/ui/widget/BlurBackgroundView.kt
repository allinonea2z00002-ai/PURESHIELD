package com.pureshield.app.ui.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.pureshield.app.R

/**
 * Blur-only background layer (added after the Run #15 revert).
 *
 * The layout gives this view `android:background="?attr/appBackground"`, so
 * it paints exactly the theme background the screen had before; on top of
 * that it shows three faint, pre-rendered [R.drawable.bg_blur_blob] light
 * blobs (plain white radial gradients, alpha 0.05-0.07, so the same subtle
 * feel on all five themes). Nothing else on the screen is touched.
 *
 * Battery-cheap by design: the blobs are pre-rendered drawables moved only
 * by translation animators (hardware-composited, no redraw, no per-frame
 * blur, no RenderEffect, no backdrop blur). Activities call [startMotion] in
 * onResume and [stopMotion] in onPause so the motion pauses off-screen.
 */
class BlurBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private data class BlobSpec(
        /** Blob diameter as a fraction of the view width. */
        val widthFrac: Float,
        /** Base position as a fraction of view width / height. */
        val xFrac: Float,
        val yFrac: Float,
        /** Peak opacity (the gradient itself fades to transparent). */
        val alpha: Float,
        /** Drift amplitude in dp, each way from the base position. */
        val driftXDp: Float,
        val driftYDp: Float,
        /** One-way drift durations; X and Y differ so blobs never sync up. */
        val durationXMs: Long,
        val durationYMs: Long
    )

    private val specs = listOf(
        // Large, top right.
        BlobSpec(0.72f, 0.52f, -0.10f, 0.07f, 30f, 22f, 34_000L, 41_000L),
        // Mid, left edge.
        BlobSpec(0.58f, -0.20f, 0.40f, 0.06f, 26f, 30f, 42_000L, 36_000L),
        // Small, bottom centre.
        BlobSpec(0.50f, 0.34f, 0.76f, 0.05f, 20f, 24f, 30_000L, 47_000L)
    )

    private val blobs = mutableListOf<ImageView>()
    private val animators = mutableListOf<ObjectAnimator>()
    private var motionWanted = false
    private var animatorsBuilt = false

    init {
        // Behind the content, never interactive.
        isClickable = false
        isFocusable = false
        for (spec in specs) {
            val blob = ImageView(context).apply {
                setImageResource(R.drawable.bg_blur_blob)
                alpha = spec.alpha
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(blob)
            blobs.add(blob)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        for (i in specs.indices) {
            val spec = specs[i]
            val size = (w * spec.widthFrac).toInt()
            blobs[i].layoutParams = LayoutParams(size, size).apply {
                leftMargin = (w * spec.xFrac).toInt()
                topMargin = (h * spec.yFrac).toInt()
            }
        }
        buildAnimators()
        if (motionWanted) startAnimators()
    }

    /** Start (or resume) the slow drift. Called from the activity's onResume. */
    fun startMotion() {
        motionWanted = true
        startAnimators()
    }

    /** Pause the drift. Called from the activity's onPause. */
    fun stopMotion() {
        motionWanted = false
        for (anim in animators) {
            if (anim.isRunning) anim.pause()
        }
    }

    override fun onDetachedFromWindow() {
        for (anim in animators) anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun startAnimators() {
        for (anim in animators) {
            when {
                anim.isPaused -> anim.resume()
                !anim.isStarted -> anim.start()
            }
        }
    }

    private fun buildAnimators() {
        if (animatorsBuilt) return
        animatorsBuilt = true
        val density = resources.displayMetrics.density
        for (i in specs.indices) {
            val spec = specs[i]
            val blob = blobs[i]
            val dx = spec.driftXDp * density
            val dy = spec.driftYDp * density
            val xAnim = ObjectAnimator.ofFloat(blob, "translationX", -dx, dx).apply {
                duration = spec.durationXMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                startDelay = i * 4000L
            }
            val yAnim = ObjectAnimator.ofFloat(blob, "translationY", -dy, dy).apply {
                duration = spec.durationYMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                startDelay = i * 4000L
            }
            animators += xAnim
            animators += yAnim
        }
    }
}
