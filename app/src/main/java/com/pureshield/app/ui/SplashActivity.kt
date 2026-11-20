package com.pureshield.app.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pureshield.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash: 1.1s, then a 0.5s fade to the dashboard. The loading bar's mint
 * segment slides left-to-right across the 120dp track while waiting.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var b: ActivitySplashBinding
    private var segmentAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        val density = resources.displayMetrics.density
        segmentAnimator = ObjectAnimator.ofFloat(
            b.loadSegment, "translationX", -48f * density, 120f * density
        ).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        lifecycleScope.launch {
            delay(1100)
            b.splashRoot.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                }
                .start()
        }
    }

    override fun onDestroy() {
        segmentAnimator?.cancel()
        segmentAnimator = null
        super.onDestroy()
    }
}
