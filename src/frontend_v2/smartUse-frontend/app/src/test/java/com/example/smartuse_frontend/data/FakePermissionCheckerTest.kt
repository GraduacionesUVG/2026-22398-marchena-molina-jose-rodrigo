package com.example.smartuse_frontend.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePermissionCheckerTest {

    @Test
    fun `reports the configured grant state and reacts to changes`() {
        val checker = FakePermissionChecker(usageAccessGranted = false)
        assertFalse(checker.hasUsageAccess())

        checker.usageAccessGranted = true

        assertTrue(checker.hasUsageAccess())
    }

    @Test
    fun `everything is granted by default`() {
        val checker = FakePermissionChecker()
        assertTrue(checker.hasUsageAccess())
        assertTrue(checker.hasNotificationPermission())
        assertTrue(checker.hasBatteryOptimizationExemption())
    }

    @Test
    fun `the permissions are independent`() {
        val checker = FakePermissionChecker(usageAccessGranted = false, notificationsGranted = true)
        assertFalse(checker.hasUsageAccess())
        assertTrue(checker.hasNotificationPermission())
        assertTrue(checker.hasBatteryOptimizationExemption())

        checker.notificationsGranted = false
        assertFalse(checker.hasNotificationPermission())
        assertTrue(checker.hasBatteryOptimizationExemption())

        checker.batteryExemptionGranted = false
        assertFalse(checker.hasBatteryOptimizationExemption())
    }
}
