package com.example.smartuse_frontend.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac

class RealKeystoreKeyManager : KeystoreKeyManager {

    override fun ensureHmacKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(HMAC_ALIAS)) return

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    override fun hmacHex(message: String): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(HMAC_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: error("HMAC key '$HMAC_ALIAS' not found — call ensureHmacKey() first")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(entry.secretKey)
        // Normalized identifier: UTF-8,
        // leading/trailing whitespace trimmed only — no case-folding.
        val digest = mac.doFinal(message.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun destroyKeys() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(HMAC_ALIAS)) ks.deleteEntry(HMAC_ALIAS)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val HMAC_ALIAS = "app_hash_hmac_key"
    }
}
