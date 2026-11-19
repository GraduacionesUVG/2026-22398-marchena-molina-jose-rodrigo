package com.example.smartuse_frontend.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeKeystoreKeyManagerTest {

    @Test
    fun `ensureHmacKey marks the key generated`() {
        val manager = FakeKeystoreKeyManager()
        assertFalse(manager.generated)

        manager.ensureHmacKey()

        assertTrue(manager.generated)
    }

    @Test
    fun `ensureHmacKey throws when configured to fail`() {
        val manager = FakeKeystoreKeyManager(shouldFail = true)
        assertThrows(IllegalStateException::class.java) { manager.ensureHmacKey() }
    }
}
