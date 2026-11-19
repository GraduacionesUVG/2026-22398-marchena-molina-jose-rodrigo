package com.example.smartuse_frontend.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class RealKeystoreKeyManagerInstrumentedTest {

    @Test
    fun ensureHmacKey_is_idempotent_and_creates_the_alias() {
        val manager = RealKeystoreKeyManager()

        manager.ensureHmacKey()
        manager.ensureHmacKey() // must not throw

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias("app_hash_hmac_key"))
    }
}
