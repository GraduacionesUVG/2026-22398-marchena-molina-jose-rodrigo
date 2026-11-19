package com.example.smartuse_frontend.ui.common

import android.provider.Settings
import com.example.smartuse_frontend.data.KeyPermission
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionFixTargetsTest {

    @Test
    fun `usage access opens this app's own toggle first, then the app list`() {
        assertEquals(
            listOf(
                FixTarget(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackageUri = true),
                FixTarget(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            ),
            PermissionFixTargets.forPermission(KeyPermission.USAGE_ACCESS),
        )
    }

    @Test
    fun `notifications open the app's notification settings first, then its details page`() {
        assertEquals(
            listOf(
                FixTarget(Settings.ACTION_APP_NOTIFICATION_SETTINGS, withPackageExtra = true),
                FixTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackageUri = true),
            ),
            PermissionFixTargets.forPermission(KeyPermission.NOTIFICATIONS),
        )
    }

    @Test
    fun `battery exemption shows the system allow dialog first, then the optimization list`() {
        assertEquals(
            listOf(
                FixTarget(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackageUri = true),
                FixTarget(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            ),
            PermissionFixTargets.forPermission(KeyPermission.BATTERY_EXEMPTION),
        )
    }

    @Test
    fun `overlay opens this app's own switch first, then the app list`() {
        assertEquals(
            listOf(
                FixTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackageUri = true),
                FixTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            ),
            PermissionFixTargets.forPermission(KeyPermission.OVERLAY),
        )
    }

    @Test
    fun `every permission has an exact target and a fallback`() {
        KeyPermission.values().forEach { assertEquals("$it", 2, PermissionFixTargets.forPermission(it).size) }
    }
}
