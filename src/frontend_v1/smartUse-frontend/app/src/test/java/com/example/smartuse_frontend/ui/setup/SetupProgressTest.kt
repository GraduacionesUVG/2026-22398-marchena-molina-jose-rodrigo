package com.example.smartuse_frontend.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupProgressTest {

    @Test
    fun `the consent, profile and permission steps are numbered in order`() {
        assertEquals(SetupProgress(current = 1, total = 5), SetupStep.Terms.progress())
        assertEquals(SetupProgress(current = 2, total = 5), SetupStep.ProfileForm.progress())
        assertEquals(SetupProgress(current = 3, total = 5), SetupStep.UsageAccess.progress())
        assertEquals(SetupProgress(current = 4, total = 5), SetupStep.NotificationAccess.progress())
        assertEquals(SetupProgress(current = 5, total = 5), SetupStep.BatteryOptimization.progress())
    }

    @Test
    fun `transient and terminal screens show no progress`() {
        val unnumbered = listOf(
            SetupStep.Registering,
            SetupStep.GeneratingKey,
            SetupStep.Done,
            SetupStep.Unsupported,
            SetupStep.RegisterError("error"),
            SetupStep.KeystoreError,
        )
        unnumbered.forEach { assertNull("$it", it.progress()) }
    }

    @Test
    fun `with the autostart step there are six numbered steps`() {
        assertEquals(SetupProgress(current = 5, total = 6), SetupStep.BatteryOptimization.progress(needsAutostartStep = true))
        assertEquals(SetupProgress(current = 6, total = 6), SetupStep.Autostart.progress(needsAutostartStep = true))
        assertEquals(SetupProgress(current = 1, total = 6), SetupStep.Terms.progress(needsAutostartStep = true))
    }

    @Test
    fun `without it the autostart step is not numbered`() {
        assertNull(SetupStep.Autostart.progress(needsAutostartStep = false))
    }
}
