package com.example.smartuse_frontend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStatusTest {

    private fun status(
        usageAccess: Boolean = true,
        notifications: Boolean = true,
        batteryExemption: Boolean = true,
        overlay: Boolean = true,
    ) = PermissionStatus(usageAccess, notifications, batteryExemption, overlay)

    @Test
    fun `everything granted is complete with nothing missing`() {
        val status = status()

        assertTrue(status.isComplete)
        assertEquals(emptySet<KeyPermission>(), status.missing)
    }

    @Test
    fun `each missing permission is reported on its own`() {
        assertEquals(setOf(KeyPermission.USAGE_ACCESS), status(usageAccess = false).missing)
        assertEquals(setOf(KeyPermission.NOTIFICATIONS), status(notifications = false).missing)
        assertEquals(setOf(KeyPermission.BATTERY_EXEMPTION), status(batteryExemption = false).missing)
        assertEquals(setOf(KeyPermission.OVERLAY), status(overlay = false).missing)
    }

    @Test
    fun `missing keeps a stable order`() {
        val status = status(usageAccess = false, notifications = false, batteryExemption = false, overlay = false)

        assertEquals(
            listOf(
                KeyPermission.USAGE_ACCESS,
                KeyPermission.NOTIFICATIONS,
                KeyPermission.BATTERY_EXEMPTION,
                KeyPermission.OVERLAY,
            ),
            status.missing.toList(),
        )
        assertFalse(status.isComplete)
    }

    @Test
    fun `isGranted is the complement of missing`() {
        val status = status(usageAccess = false)

        assertFalse(status.isGranted(KeyPermission.USAGE_ACCESS))
        assertTrue(status.isGranted(KeyPermission.NOTIFICATIONS))
        assertTrue(status.isGranted(KeyPermission.BATTERY_EXEMPTION))
        assertTrue(status.isGranted(KeyPermission.OVERLAY))
    }

    @Test
    fun `status reads the four checks from a PermissionChecker`() {
        val checker = FakePermissionChecker(
            usageAccessGranted = false,
            notificationsGranted = true,
            batteryExemptionGranted = false,
            overlayGranted = false,
        )

        assertEquals(
            PermissionStatus(usageAccess = false, notifications = true, batteryExemption = false, overlay = false),
            checker.status(),
        )
    }
}
