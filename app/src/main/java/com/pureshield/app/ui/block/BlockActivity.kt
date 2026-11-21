package com.pureshield.app.ui.block

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.databinding.ActivityBlockBinding
import com.pureshield.app.service.BlockReason
import com.pureshield.app.service.UnlockSession
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.ui.pin.PinActivity
import com.pureshield.app.ui.pin.PinEntrySheet
import com.pureshield.app.util.BnDigits
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Block screen: shown by the accessibility service when a blocked app or site
 * opens.
 *
 * Two modes, chosen per intent:
 * - Lock mode (a time session or the location lock is active, or the reason
 *   is SITE / KEYWORD / FEED / SELF_PROTECT): the original screen — the
 *   shield, "<App/Site> ব্লক করা হয়েছে", the remaining lock time (Bengali
 *   MM:SS, ticking every 500ms), an emergency unlock button (partner PIN
 *   sheet) and a "home" button. Back is NOT dismissible while locked — the
 *   press is consumed. "হোমে ফিরুন" is the way out; the service re-shows
 *   this screen if the blocked app is opened again. When no lock is active,
 *   Back goes home like the button.
 * - Auth mode (no lock active, reason LOCK, App Lock on): the App Lock
 *   authentication screen. The user opens the app with the partner PIN
 *   (same keypad sheet as the emergency flow, in its app-auth mode) or with
 *   BiometricPrompt (PIN / biometrics / device credential). A correct PIN
 *   or a successful biometric marks the app unlocked for this foreground
 *   session and launches it; a wrong PIN shows an error and stays on the
 *   screen; Back goes home. If no partner PIN is set and biometrics are
 *   unavailable, the screen offers the PIN setup instead of a dead end.
 *
 * Auth never opens the app while a lock is active — that would defeat the
 * lock. A lock that starts while the auth screen is showing (repeat lock,
 * location lock) turns the screen into the lock screen.
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

        /**
         * Must match PureShieldAccessibilityService.EXTRA_PKG ("extra_pkg"):
         * the blocked app's package name, for the App Lock auth screen.
         */
        const val EXTRA_PKG = "extra_pkg"
    }

    private lateinit var b: ActivityBlockBinding

    private var locked = false
    private var authMode = false
    private var reason: BlockReason? = null
    private var targetPkg: String = ""
    private var targetLabel: String = ""
    private var pendingPinSheet = false
    private var pendingAuthSheet = false

    private val biometricPrompt by lazy {
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showAuthError(getString(R.string.auth_bio_failed))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // A cancel (user pressed away, or the sensor gave up) is
                    // silent: stay on the screen. Real errors get a line.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        showAuthError(errString.toString())
                    }
                }
            }
        )
    }

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
        b.authHomeButton.setOnClickListener { goHome() }
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
        b.authPinButton.setOnClickListener { showAuthPinSheet() }
        b.authBioButton.setOnClickListener { showBiometricPrompt() }
        b.authSetPinButton.setOnClickListener {
            pendingAuthSheet = true
            startActivity(
                Intent(this, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET)
            )
        }

        supportFragmentManager.setFragmentResultListener(
            PinEntrySheet.REQUEST_KEY, this
        ) { _, bundle ->
            when {
                bundle.getBoolean(PinEntrySheet.RESULT_UNLOCKED, false) -> {
                    Toast.makeText(this, R.string.pin_unlocked, Toast.LENGTH_SHORT).show()
                    goHome()
                }
                bundle.getBoolean(PinEntrySheet.RESULT_APP_UNLOCKED, false) -> onAuthSuccess()
                bundle.getBoolean(PinEntrySheet.RESULT_AUTH_WRONG, false) -> {
                    showAuthError(getString(R.string.pin_wrong))
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = Graph.lockManager.snapshotNow()
                    locked = snap.protecting
                    b.remainingText.text = BnDigits.formatHMSLocalized(snap.remainingMs)
                    if (snap.active && snap.remainingMs <= 0L) {
                        Graph.lockManager.checkpoint()
                    }
                    applyMode()
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
        b.blurBackground.startMotion()
        if (pendingPinSheet && Graph.secure.hasPartnerPin()) {
            pendingPinSheet = false
            showPinSheet()
        }
        if (pendingAuthSheet && Graph.secure.hasPartnerPin()) {
            pendingAuthSheet = false
            refreshAuthButtons()
            showAuthPinSheet()
        }
    }

    override fun onPause() {
        b.blurBackground.stopMotion()
        super.onPause()
    }

    private fun bind(intent: Intent) {
        targetLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        targetPkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        reason = runCatching {
            BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty())
        }.getOrNull()
        b.blockTitle.text = getString(
            R.string.blockscreen_title,
            targetLabel.ifBlank { getString(R.string.app_name) }
        )
        b.authTitle.text = getString(
            R.string.auth_title,
            targetLabel.ifBlank { getString(R.string.app_name) }
        )
        // The app's own icon when the package is known, else the shield.
        b.authAppIcon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(targetPkg) }.getOrNull()
                ?: getDrawable(R.drawable.ic_logo_splash)
        )
        refreshAuthButtons()
        b.authError.isVisible = false
        applyMode()
    }

    /** Lock screen vs auth screen. A lock starting mid-screen switches modes. */
    private fun applyMode() {
        // SITE / KEYWORD / FEED / SELF_PROTECT always keep the lock screen;
        // LOCK shows the auth screen only while no lock is active.
        val showLock = locked || reason != BlockReason.LOCK
        authMode = !showLock
        b.lockGroup.isVisible = showLock
        b.authGroup.isVisible = !showLock
    }

    private fun refreshAuthButtons() {
        val bioOk = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
        val hasPin = Graph.secure.hasPartnerPin()
        b.authBioButton.isVisible = bioOk
        b.authPinButton.isVisible = hasPin
        // No PIN and no biometrics: offer the PIN setup, never a dead end.
        b.authSetPinButton.isVisible = !hasPin && !bioOk
    }

    private fun showPinSheet() {
        PinEntrySheet.newInstance().show(supportFragmentManager, PinEntrySheet.TAG)
    }

    private fun showAuthPinSheet() {
        if (!Graph.secure.hasPartnerPin()) return
        PinEntrySheet.newInstance(PinEntrySheet.MODE_APP_AUTH)
            .show(supportFragmentManager, PinEntrySheet.TAG)
    }

    private fun showBiometricPrompt() {
        // No negative button: DEVICE_CREDENTIAL supplies the fallback, and
        // setting one with device credential is an error.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(
                getString(
                    R.string.auth_bio_title,
                    targetLabel.ifBlank { getString(R.string.app_name) }
                )
            )
            .setSubtitle(getString(R.string.auth_bio_sub))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        runCatching { biometricPrompt.authenticate(info) }
    }

    /** PIN or biometrics accepted: unlock this foreground session and open the app. */
    private fun onAuthSuccess() {
        if (targetPkg.isBlank()) {
            goHome()
            return
        }
        // Mark unlocked BEFORE launching, so the service's own window event
        // for the app does not re-block it.
        UnlockSession.unlock(targetPkg)
        val launch = packageManager.getLaunchIntentForPackage(targetPkg)
        if (launch == null) {
            Toast.makeText(this, R.string.auth_no_launch, Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        finish()
    }

    private fun showAuthError(text: String) {
        b.authError.text = text
        b.authError.isVisible = true
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
