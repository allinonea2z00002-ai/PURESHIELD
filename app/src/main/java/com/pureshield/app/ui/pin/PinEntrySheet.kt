package com.pureshield.app.ui.pin

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pureshield.app.Graph
import com.pureshield.app.R
import com.pureshield.app.databinding.SheetPinBinding
import com.pureshield.app.ui.attrColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Partner PIN emergency-unlock sheet (demo PIN sheet, minus the demo's
 * hard-coded 1234): 4 dots fill mint as digits are entered; the keypad has
 * round 58dp keys 1-9, 'বন্ধ' (close), 0, '⌫' (delete).
 *
 * Correct PIN (verified by LockManager.emergencyUnlock, which checks the
 * SHA-256 hash in SecureStore and ends the lock) -> 250ms delay, close, and
 * a fragment result (REQUEST_KEY / RESULT_UNLOCKED) for the caller. Wrong at
 * 4 digits -> dots turn red, the sheet shakes (.4s), after 420ms the PIN
 * clears + toast 'ভুল PIN — আবার চেষ্টা করুন'.
 *
 * Callers must check Graph.secure.hasPartnerPin() first: if no PIN is set
 * yet, they route to PinActivity's set flow instead of showing this sheet.
 */
class PinEntrySheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "pin_entry_sheet"
        const val REQUEST_KEY = "pin_entry_sheet"
        const val RESULT_UNLOCKED = "unlocked"

        fun newInstance() = PinEntrySheet()
    }

    private var _b: SheetPinBinding? = null
    private val b get() = _b!!
    private lateinit var dots: List<View>

    private val pin = StringBuilder()
    private var verifying = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = SheetPinBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Transparent window so our own card (top-only rounded corners) shows.
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        b.sheetCard.shapeAppearanceModel = b.sheetCard.shapeAppearanceModel.toBuilder()
            .setBottomLeftCornerSize(0f)
            .setBottomRightCornerSize(0f)
            .build()

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
        b.keyClose.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun onDigit(digit: Char) {
        if (verifying || pin.length >= 4) return
        pin.append(digit)
        updateDots()
        if (pin.length == 4) verify()
    }

    private fun onDelete() {
        if (verifying || pin.isEmpty()) return
        pin.deleteCharAt(pin.length - 1)
        updateDots()
    }

    private fun verify() {
        verifying = true
        val entered = pin.toString()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = Graph.lockManager.emergencyUnlock(entered)
            if (!isAdded) return@launch
            if (ok) {
                delay(250)
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_UNLOCKED to true)
                )
                dismiss()
            } else {
                setDotsWrong()
                ObjectAnimator.ofFloat(b.sheetDots, "translationX", 0f, -12f, 12f, -8f, 8f, 0f)
                    .setDuration(400)
                    .start()
                Toast.makeText(requireContext(), R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                delay(420)
                pin.clear()
                verifying = false
                updateDots()
            }
        }
    }

    private fun updateDots() {
        val mint = requireContext().attrColor(R.attr.mintColor)
        val faint = requireContext().attrColor(R.attr.faintColor)
        dots.forEachIndexed { index, dot ->
            ViewCompat.setBackgroundTintList(
                dot,
                ColorStateList.valueOf(if (index < pin.length) mint else faint)
            )
        }
    }

    private fun setDotsWrong() {
        val red = requireContext().attrColor(R.attr.redColor)
        dots.forEach { dot ->
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(red))
        }
    }
}
