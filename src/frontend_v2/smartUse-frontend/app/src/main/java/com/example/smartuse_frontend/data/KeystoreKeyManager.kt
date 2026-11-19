package com.example.smartuse_frontend.data

/**
 * Generates the per-install HMAC-SHA256 key used to hash app identifiers before
 * they are written or synced. The key is non-exportable and never leaves the
 * device, so a hash cannot be reversed or matched across installs.
 */
interface KeystoreKeyManager {
    /** Creates the key if absent; no-op if it already exists. Throws on Keystore failure. */
    fun ensureHmacKey()

    /**
     * lowercase_hex(HMAC-SHA256(key = per-install key, message = message)),
     * Call [ensureHmacKey] first.
     */
    fun hmacHex(message: String): String

    /** Deletes the HMAC key once opt-out is confirmed — capture is permanently over. No-op if already absent. */
    fun destroyKeys()
}
