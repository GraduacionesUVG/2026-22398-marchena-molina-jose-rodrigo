package com.example.smartuse_frontend.data

class FakePermissionChecker(
    var usageAccessGranted: Boolean = true,
    var notificationsGranted: Boolean = true,
    var batteryExemptionGranted: Boolean = true,
) : PermissionChecker {
    override fun hasUsageAccess(): Boolean = usageAccessGranted
    override fun hasNotificationPermission(): Boolean = notificationsGranted
    override fun hasBatteryOptimizationExemption(): Boolean = batteryExemptionGranted
}
