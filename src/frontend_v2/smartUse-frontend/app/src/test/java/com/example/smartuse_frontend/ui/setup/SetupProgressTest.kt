package com.example.smartuse_frontend.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupProgressTest {

    @Test
    fun `the consent, profile and permission steps are numbered in order`() {
        assertEquals(SetupProgress(current = 1, total = 6), SetupStep.Terms.progress())
        assertEquals(SetupProgress(current = 2, total = 6), SetupStep.ProfileForm.progress())
        assertEquals(SetupProgress(current = 3, total = 6), SetupStep.UsageAccess.progress())
        assertEquals(SetupProgress(current = 4, total = 6), SetupStep.NotificationAccess.progress())
        assertEquals(SetupProgress(current = 5, total = 6), SetupStep.BatteryOptimization.progress())
        assertEquals(SetupProgress(current = 6, total = 6), SetupStep.OverlayAccess.progress())
    }

    @Test
    fun `transient and terminal screens show no progress`() {
        val unnumbered = listOf(
            SetupStep.Registering,
            SetupStep.GeneratingKey,
            SetupStep.Done,
            SetupStep.RegisterError("error"),
            SetupStep.KeystoreError,
        )
        unnumbered.forEach { assertNull("$it", it.progress()) }
    }

    @Test
    fun `with the autostart step there are seven numbered steps`() {
        assertEquals(SetupProgress(current = 6, total = 7), SetupStep.OverlayAccess.progress(needsAutostartStep = true))
        assertEquals(SetupProgress(current = 7, total = 7), SetupStep.Autostart.progress(needsAutostartStep = true))
        assertEquals(SetupProgress(current = 1, total = 7), SetupStep.Terms.progress(needsAutostartStep = true))
    }

    @Test
    fun `without it the autostart step is not numbered`() {
        assertNull(SetupStep.Autostart.progress(needsAutostartStep = false))
    }

    @Test
    fun `the earlier-usage step is counted only while shown`() {
        assertEquals(SetupProgress(current = 7, total = 7), SetupStep.EarlierUsage.progress())
        assertEquals(SetupProgress(current = 8, total = 8), SetupStep.EarlierUsage.progress(needsAutostartStep = true))
        assertEquals(SetupProgress(current = 6, total = 6), SetupStep.OverlayAccess.progress())
        assertEquals(SetupProgress(current = 7, total = 7), SetupStep.Autostart.progress(needsAutostartStep = true))
    }
}
