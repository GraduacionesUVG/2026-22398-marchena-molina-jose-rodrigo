package com.example.smartuse_frontend.data

import java.security.MessageDigest

class FakeKeystoreKeyManager(private val shouldFail: Boolean = false) : KeystoreKeyManager {
    var generated: Boolean = false
        private set

    var destroyed: Boolean = false
        private set

    override fun ensureHmacKey() {
        check(!shouldFail) { "keystore unavailable" }
        generated = true
    }

    // Not a real HMAC (no secret key involved) — deterministic stand-in for
    // tests/fakes only.
    override fun hmacHex(message: String): String {
        check(generated) { "call ensureHmacKey() first" }
        return MessageDigest.getInstance("SHA-256")
            .digest(message.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override fun destroyKeys() {
        destroyed = true
    }
}
