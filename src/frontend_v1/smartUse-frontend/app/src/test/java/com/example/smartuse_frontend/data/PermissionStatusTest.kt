package com.example.smartuse_frontend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStatusTest {

    @Test
    fun `everything granted is complete with nothing missing`() {
        val status = PermissionStatus(usageAccess = true, notifications = true, batteryExemption = true)

        assertTrue(status.isComplete)
        assertEquals(emptySet<KeyPermission>(), status.missing)
    }

    @Test
    fun `each missing permission is reported on its own`() {
        assertEquals(
            setOf(KeyPermission.USAGE_ACCESS),
            PermissionStatus(usageAccess = false, notifications = true, batteryExemption = true).missing,
        )
        assertEquals(
            setOf(KeyPermission.NOTIFICATIONS),
            PermissionStatus(usageAccess = true, notifications = false, batteryExemption = true).missing,
        )
        assertEquals(
            setOf(KeyPermission.BATTERY_EXEMPTION),
            PermissionStatus(usageAccess = true, notifications = true, batteryExemption = false).missing,
        )
    }

    @Test
    fun `missing keeps a stable order`() {
        val status = PermissionStatus(usageAccess = false, notifications = false, batteryExemption = false)

        assertEquals(
            listOf(KeyPermission.USAGE_ACCESS, KeyPermission.NOTIFICATIONS, KeyPermission.BATTERY_EXEMPTION),
            status.missing.toList(),
        )
        assertFalse(status.isComplete)
    }

    @Test
    fun `isGranted is the complement of missing`() {
        val status = PermissionStatus(usageAccess = false, notifications = true, batteryExemption = true)

        assertFalse(status.isGranted(KeyPermission.USAGE_ACCESS))
        assertTrue(status.isGranted(KeyPermission.NOTIFICATIONS))
        assertTrue(status.isGranted(KeyPermission.BATTERY_EXEMPTION))
    }

    @Test
    fun `status reads the three checks from a PermissionChecker`() {
        val checker = FakePermissionChecker(
            usageAccessGranted = false,
            notificationsGranted = true,
            batteryExemptionGranted = false,
        )

        assertEquals(
            PermissionStatus(usageAccess = false, notifications = true, batteryExemption = false),
            checker.status(),
        )
    }
}
