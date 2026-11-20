package com.pureshield.app.ui.block

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.databinding.ActivityBlockBinding
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.ui.pin.PinActivity
import com.pureshield.app.ui.pin.PinEntrySheet
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Block screen: shown by the accessibility service when a blocked app or site
 * opens while locked. Shows the shield, "<App/Site> ব্লক করা হয়েছে", the
 * remaining lock time (Bengali MM:SS, ticking every 500ms), an emergency
 * unlock button (partner PIN sheet) and a "home" button.
 *
 * Back is NOT dismissible while locked — the press is consumed. "হোমে ফিরুন"
 * is the way out; the service re-shows this screen if the blocked app is
 * opened again. When no lock is active, Back goes home like the button.
 */
class BlockActivity : AppCompatActivity() {

    companion object {
        /**
         * Must match PureShieldAccessibilityService.EXTRA_LABEL ("extra_label"):
         * the label of the blocked app (for a site/keyword/feed block, the
         * label of the host app, e.g. the browser).
         */
        const val EXTRA_LABEL = "extra_label"

        /**
         * Must match PureShieldAccessibilityService.EXTRA_REASON ("extra_reason"):
         * a BlockReason name (LOCK, SITE, KEYWORD, FEED, SELF_PROTECT).
         */
        const val EXTRA_REASON = "extra_reason"
    }

    private lateinit var b: ActivityBlockBinding

    private var locked = false
    private var pendingPinSheet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(b.root)

        bind(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!locked) goHome()
                // While locked the press is consumed: no dismissing.
            }
        })

        b.homeButton.setOnClickListener { goHome() }
        b.emergencyButton.setOnClickListener {
            if (!Graph.secure.hasPartnerPin()) {
                pendingPinSheet = true
                startActivity(
                    Intent(this, PinActivity::class.java)
                        .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET)
                )
            } else {
                showPinSheet()
            }
        }

        supportFragmentManager.setFragmentResultListener(
            PinEntrySheet.REQUEST_KEY, this
        ) { _, bundle ->
            if (bundle.getBoolean(PinEntrySheet.RESULT_UNLOCKED, false)) {
                Toast.makeText(this, R.string.pin_unlocked, Toast.LENGTH_SHORT).show()
                goHome()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = Graph.lockManager.snapshotNow()
                    locked = snap.active
                    b.remainingText.text = BnDigits.formatHMS(snap.remainingMs)
                    if (snap.active && snap.remainingMs <= 0L) {
                        Graph.lockManager.checkpoint()
                    }
                    delay(500)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onResume() {
        super.onResume()
        if (pendingPinSheet && Graph.secure.hasPartnerPin()) {
            pendingPinSheet = false
            showPinSheet()
        }
    }

    private fun bind(intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        b.blockTitle.text = getString(
            R.string.blockscreen_title,
            label.ifBlank { getString(R.string.app_name) }
        )
    }

    private fun showPinSheet() {
        PinEntrySheet.newInstance().show(supportFragmentManager, PinEntrySheet.TAG)
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
