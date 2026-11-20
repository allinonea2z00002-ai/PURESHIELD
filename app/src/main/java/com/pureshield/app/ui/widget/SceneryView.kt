package com.pureshield.app.ui.widget

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.pureshield.app.R
import com.pureshield.app.ui.attrBoolean
import com.pureshield.app.ui.attrColor

/**
 * Animated blurred background (demo findings §1 / §5), wired to the
 * per-theme `decorBackground` drawable that was previously consumed nowhere.
 *
 * What it draws, per theme (all values are the demo's):
 * - Static base: the theme's `decorBackground` (Original grid, Dawn/Jungle/
 *   Ocean hills + sky, Holo starfield, Zen transparent).
 * - Sun pulse (Dawn/Jungle/Splash only — Ocean and Zen hide the sun in the
 *   demo): a 190dp radial-gradient disc at top −60dp / right −46dp, scaling
 *   1.0 → 1.07 on a 9s ease-in-out loop (`sunPulse`).
 * - Drifting clouds (Dawn/Jungle/Splash): two soft pills (120×34dp at
 *   top 64dp, 90×26dp at top 150dp) translating across the screen in 52s /
 *   74s linear, infinite; the second starts 30s into its journey (the
 *   demo's −30s delay). Ocean shows drifting bubbles (26dp / 15dp circles)
 *   on the same timings instead of clouds.
 * - Original: the 28dp grid drifts (translation 0 → −28dp over 60s, seamless
 *   because the grid repeats every 28dp) — the demo's static radial glow is
 *   already part of the app background colour.
 * - Holo: a slow cyan glow blob drifts across the static starfield.
 * - Zen: no pattern, no shadows (demo §5.7) — only lavender hills at the
 *   bottom and a slow, faint mist drift.
 *
 * Battery: no per-frame blur is ever computed and nothing is redrawn per
 * frame. The decor is a static pre-rendered drawable; only small views are
 * moved/scaled by hardware-accelerated property animators (translationX /
 * scale), which the compositor handles without invalidating the view tree.
 * Animations pause when the activity pauses ([pauseAnimations], called from
 * each host activity's onPause), when the window hides, and when the view
 * detaches.
 *
 * Dark mode follows the demo: clouds drop to ~14% opacity, the sun to 50%,
 * and the light-sky decor is dimmed under a translucent app-background
 * overlay (the decor drawables themselves are day-coloured).
 */
class SceneryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpI(v: Float): Int = (v * density).toInt()

    private val decorResId: Int
    private val showScenery: Boolean
    private val isNight: Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val animators = mutableListOf<Animator>()
    private var cloudAnimatorsStarted = false

    private var decorView: View? = null
    private var cloud1: View? = null
    private var cloud2: View? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false

        val tv = TypedValue()
        decorResId = if (context.theme.resolveAttribute(R.attr.decorBackground, tv, true)) {
            tv.resourceId
        } else {
            0
        }
        showScenery = context.attrBoolean(R.attr.showScenery, false)

        buildLayers()
    }

    private fun buildLayers() {
        // 1. Static decor base (grid / hills / starfield / transparent).
        if (decorResId != 0) {
            val decor = View(context).apply {
                background = ContextCompat.getDrawable(context, decorResId)
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                )
            }
            decorView = decor
            addView(decor)
            // Original grid drift: seamless 28dp loop (see class KDoc).
            if (decorResId == R.drawable.decor_grid) {
                val drift = ObjectAnimator.ofFloat(decor, "translationX", 0f, -dp(28f)).apply {
                    duration = 60_000
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                }
                animators += drift
            }
        }

        // 2. Night dimming for the light-sky decors (demo dark mode).
        if (isNight && showScenery) {
            val dim = View(context).apply {
                setBackgroundColor(context.attrColor(R.attr.appBackground))
                alpha = 0.55f
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                )
            }
            addView(dim)
        }

        // 3. Zen: hills + mist only (no sun, no clouds, no pattern).
        if (decorResId == R.drawable.decor_plain) {
            addHill(dpI(560f), dpI(200f), Gravity.BOTTOM or Gravity.START, 110f, 100f, 0x59_8F83C4)
            addHill(dpI(560f), dpI(200f), Gravity.BOTTOM or Gravity.END, 130f, 120f, 0x4C_7C6BB0)
            val mist = View(context).apply {
                background = ovalDrawable(0x1F_FFFFFF)
                layoutParams = LayoutParams(dpI(220f), dpI(64f)).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    leftMargin = dpI(40f)
                }
            }
            addView(mist)
            animators += ObjectAnimator.ofFloat(mist, "translationX", -dp(40f), dp(40f)).apply {
                duration = 90_000
                interpolator = LinearInterpolator()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            startAnimators()
            return
        }

        // 4. Holo: drifting cyan glow blob over the static starfield.
        if (decorResId == R.drawable.decor_starfield) {
            val blob = View(context).apply {
                background = radialDrawable(
                    intArrayOf(0x33_00E5FF, Color.TRANSPARENT), 130f
                )
                layoutParams = LayoutParams(dpI(260f), dpI(260f)).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(blob)
            animators += ObjectAnimator.ofFloat(blob, "translationX", -dp(70f), dp(70f)).apply {
                duration = 40_000
                interpolator = LinearInterpolator()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            startAnimators()
            return
        }

        // 5. Sun pulse (Dawn peach, Jungle warm, Splash warm; Ocean/Zen none).
        if (showScenery) {
            val sunColors = when (decorResId) {
                R.drawable.decor_hills_lavender -> intArrayOf(0xF2FFD096.toInt(), 0x59F6BEA0)
                R.drawable.decor_hills_jungle -> intArrayOf(0xE6FFECAA.toInt(), 0x4DDCEDB4)
                R.drawable.decor_hills_sun -> intArrayOf(0xF2F6E8C9.toInt(), 0x59F6E8C9)
                else -> null // Ocean: sun hidden in the demo.
            }
            if (sunColors != null) {
                val sun = View(context).apply {
                    background = radialDrawable(
                        intArrayOf(sunColors[0], sunColors[1], Color.TRANSPARENT), 95f
                    )
                    layoutParams = LayoutParams(dpI(190f), dpI(190f)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = -dpI(60f)
                        marginEnd = -dpI(46f)
                    }
                    if (isNight) alpha = 0.5f
                }
                addView(sun)
                // Sun pulse: scale 1.0 -> 1.07, 9s ease-in-out, infinite
                // reverse (demo `sunPulse`). Driven by one ValueAnimator so
                // it can be paused/resumed with the rest.
                val pulseDriver = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 9_000
                    interpolator = AccelerateDecelerateInterpolator()
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        // ease-in-out 1.0 -> 1.07 across the 0..1 fraction.
                        val f = it.animatedValue as Float
                        val s = 1f + 0.07f * f
                        sun.scaleX = s
                        sun.scaleY = s
                    }
                }
                animators += pulseDriver
            }

            // 6. Drifting clouds — or bubbles for Ocean.
            val isOcean = decorResId == R.drawable.decor_ocean
            val cloudBase: Int = when (decorResId) {
                R.drawable.decor_hills_lavender -> 0xFFFFF5EC.toInt()
                else -> Color.WHITE
            }
            cloud1 = makeDriftShape(isOcean, 120f, 34f, 64f, cloudBase, 0.55f)
            cloud2 = makeDriftShape(isOcean, 90f, 26f, 150f, cloudBase, 0.40f)
            cloud1?.let { addView(it) }
            cloud2?.let { addView(it) }
        }

        startAnimators()
    }

    private fun addHill(w: Int, h: Int, gravity: Int, bottomDp: Float, sideDp: Float, color: Int) {
        val hill = View(context).apply {
            background = ovalDrawable(color)
            layoutParams = LayoutParams(w, h).apply {
                this.gravity = gravity
                bottomMargin = -dpI(bottomDp)
                if (gravity and Gravity.START != 0) leftMargin = -dpI(sideDp)
                if (gravity and Gravity.END != 0) rightMargin = -dpI(sideDp)
            }
        }
        addView(hill)
    }

    private fun ovalDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun radialDrawable(colors: IntArray, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(radiusDp)
            this.colors = colors
        }

    /** One drifting shape. Clouds are tinted pills; Ocean shapes are bubbles. */
    private fun makeDriftShape(
        isBubble: Boolean,
        wDp: Float,
        hDp: Float,
        topDp: Float,
        baseColor: Int,
        alpha01: Float
    ): View {
        val nightAlpha = if (isNight) alpha01 * 0.25f else alpha01
        return View(context).apply {
            if (isBubble) {
                background = ContextCompat.getDrawable(context, R.drawable.bg_bubble)
                // Bubble sizes: 26dp / 15dp (demo §5.5); the passed w/h are
                // the cloud sizes, so shrink to bubble size here.
                val size = if (wDp > 100f) dpI(26f) else dpI(15f)
                layoutParams = LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = dpI(topDp)
                    leftMargin = -dpI(60f)
                }
                alpha = if (isNight) 0.6f else 1f
            } else {
                background = ContextCompat.getDrawable(context, R.drawable.bg_cloud)
                backgroundTintList = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(baseColor, (nightAlpha * 255f).toInt())
                )
                layoutParams = LayoutParams(dpI(wDp), dpI(hDp)).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = dpI(topDp)
                    leftMargin = -dpI(160f)
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || cloudAnimatorsStarted) return
        // Original grid: the decor view needs 28dp of extra width on the
        // right so the −28dp drift never exposes a bare strip. The width
        // check keeps this idempotent across re-layouts (e.g. rotation).
        if (decorResId == R.drawable.decor_grid) {
            val lp = decorView?.layoutParams
            if (lp != null && lp.width != w + dpI(28f)) {
                lp.width = w + dpI(28f)
                decorView?.layoutParams = lp
            }
        }
        val c1 = cloud1
        val c2 = cloud2
        if (c1 != null && c2 != null) {
            // Travel: from the −160dp start (or −60dp for bubbles) across
            // the screen and off the right edge — demo driftSm 0 → 560px
            // over 52s (c1) / 74s (c2), linear, infinite.
            val travel = w + dp(200f)
            val a1 = ObjectAnimator.ofFloat(c1, "translationX", 0f, travel).apply {
                duration = 52_000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
            val a2 = ObjectAnimator.ofFloat(c2, "translationX", 0f, travel).apply {
                duration = 74_000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
            animators += a1
            animators += a2
            cloudAnimatorsStarted = true
            if (isAttachedToWindow && visibility == VISIBLE) {
                a1.start()
                a2.start()
                // Demo c2 delay −30s: start it 30s into its 74s journey.
                a2.currentPlayTime = 30_000
            }
        }
    }

    private fun startAnimators() {
        // Cloud animators are created + started from onSizeChanged (they
        // need the view width); everything else starts here.
        for (a in animators) {
            if (a is ValueAnimator && !a.isStarted && !isCloudAnimator(a)) a.start()
        }
    }

    private fun isCloudAnimator(a: ValueAnimator): Boolean =
        a.duration == 52_000L || a.duration == 74_000L

    /** Pause every scenery animator (call from the host activity's onPause). */
    fun pauseAnimations() {
        for (a in animators) {
            if (a is ValueAnimator && a.isRunning) a.pause()
        }
    }

    /** Resume every scenery animator (call from the host activity's onResume). */
    fun resumeAnimations() {
        for (a in animators) {
            if (a is ValueAnimator) {
                when {
                    a.isPaused -> a.resume()
                    !a.isStarted -> {
                        a.start()
                        // Demo c2 delay −30s: a freshly started c2 begins
                        // 30s into its 74s journey.
                        if (a.duration == 74_000L) a.currentPlayTime = 30_000
                    }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) resumeAnimations()
    }

    override fun onDetachedFromWindow() {
        for (a in animators) {
            if (a is ValueAnimator) a.cancel()
        }
        // Cloud animators are re-created from the fresh width on the next
        // layout pass; drop the cancelled ones so they are not restarted
        // twice. The other animators are re-started by resumeAnimations().
        animators.removeAll { it is ValueAnimator && isCloudAnimator(it) }
        cloudAnimatorsStarted = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resumeAnimations() else pauseAnimations()
    }
}
