package com.pureshield.app.ui.pin

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.databinding.ActivityPinBinding
import com.pureshield.app.ui.ThemeManager
import com.pureshield.app.ui.attrColor

/**
 * Partner PIN set/change flow: enter a 4-digit PIN, then confirm it. The PIN
 * is saved only as its SHA-256 hash via SecureStore — the raw PIN is never
 * stored. Returns RESULT_OK when a PIN was saved; the caller (Settings, or
 * the Time Lock emergency flow) decides what happens next.
 */
class PinActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SET = "set"
        const val MODE_CHANGE = "change"
    }

    private enum class Phase { ENTER, CONFIRM }

    private lateinit var b: ActivityPinBinding
    private lateinit var dots: List<View>

    private var mode: String = MODE_SET
    private var phase: Phase = Phase.ENTER
    private var firstPin: String = ""
    private val pin = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityPinBinding.inflate(layoutInflater)
        setContentView(b.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SET
        b.pinTitle.setText(
            if (mode == MODE_CHANGE) R.string.pin_change_title else R.string.pin_set_title
        )
        b.pinSub.setText(R.string.pin_set_sub)

        dots = listOf(b.dot1, b.dot2, b.dot3, b.dot4)

        val digits = mapOf(
            b.key1 to '1', b.key2 to '2', b.key3 to '3',
            b.key4 to '4', b.key5 to '5', b.key6 to '6',
            b.key7 to '7', b.key8 to '8', b.key9 to '9',
            b.key0 to '0'
        )
        for ((key, digit) in digits) {
            key.setOnClickListener { onDigit(digit) }
        }
        b.keyDelete.setOnClickListener { onDelete() }
        b.keyClose.setOnClickListener { finish() }
    }

    private fun onDigit(digit: Char) {
        if (pin.length >= 4) return
        pin.append(digit)
        updateDots()
        if (pin.length == 4) onPinComplete()
    }

    private fun onDelete() {
        if (pin.isNotEmpty()) {
            pin.deleteCharAt(pin.length - 1)
            updateDots()
        }
    }

    private fun onPinComplete() {
        val entered = pin.toString()
        when (phase) {
            Phase.ENTER -> {
                firstPin = entered
                pin.clear()
                phase = Phase.CONFIRM
                b.pinSub.setText(R.string.pin_confirm_sub)
                updateDots()
            }
            Phase.CONFIRM -> {
                if (entered == firstPin && Graph.secure.setPartnerPin(entered)) {
                    Toast.makeText(
                        this,
                        if (mode == MODE_CHANGE) R.string.pin_changed_done else R.string.pin_set_done,
                        Toast.LENGTH_SHORT
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    wrongPin()
                }
            }
        }
    }

    /** Mismatch: red dots, shake, toast, then back to the first entry. */
    private fun wrongPin() {
        setDotsWrong()
        ObjectAnimator.ofFloat(b.pinDots, "translationX", 0f, -12f, 12f, -8f, 8f, 0f)
            .setDuration(400)
            .start()
        Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
        b.pinDots.postDelayed(
            {
                pin.clear()
                firstPin = ""
                phase = Phase.ENTER
                b.pinSub.setText(R.string.pin_set_sub)
                updateDots()
            },
            420
        )
    }

    private fun updateDots() {
        val mint = attrColor(R.attr.mintColor)
        val faint = attrColor(R.attr.faintColor)
        dots.forEachIndexed { index, dot ->
            ViewCompat.setBackgroundTintList(
                dot,
                ColorStateList.valueOf(if (index < pin.length) mint else faint)
            )
        }
    }

    private fun setDotsWrong() {
        val red = attrColor(R.attr.redColor)
        dots.forEach { dot ->
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(red))
        }
    }
}
