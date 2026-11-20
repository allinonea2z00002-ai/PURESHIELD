package com.pureshield.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the partner PIN used for emergency unlock.
 * Only the SHA-256 hash is ever stored — the raw PIN is never written anywhere.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "pureshield_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore problems must not crash the app; fall back to private (non-encrypted) storage
        // and keep working. The hash is still only readable by this app.
        context.getSharedPreferences("pureshield_secure_fallback", Context.MODE_PRIVATE)
    }

    private var partnerPinHash: String
        get() = prefs.getString(KEY_PARTNER_PIN_HASH, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PARTNER_PIN_HASH, value).apply() }

    /**
     * Stores [pin] as its SHA-256 hash. Returns false when [pin] is not exactly 4 digits
     * (nothing is stored in that case).
     */
    fun setPartnerPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        partnerPinHash = sha256Hex(pin)
        return true
    }

    /** True when [pin] hashes to the stored partner PIN. */
    fun verifyPartnerPin(pin: String): Boolean =
        partnerPinHash.isNotBlank() && partnerPinHash == sha256Hex(pin)

    /** True when a partner PIN has been set. */
    fun hasPartnerPin(): Boolean = partnerPinHash.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PARTNER_PIN_HASH = "partner_pin_hash"

        fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it.isDigit() }

        /** Lowercase hex SHA-256 of [input]. */
        fun sha256Hex(input: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
