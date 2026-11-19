package com.example.smartuse_frontend.data

/** The permissions the study depends on after setup, with the Spanish name shown to the participant. */
enum class KeyPermission(val labelEs: String) {
    USAGE_ACCESS("acceso al uso"),
    NOTIFICATIONS("notificaciones"),
    BATTERY_EXEMPTION("ejecución en segundo plano"),
    OVERLAY("mostrar sobre otras apps"),
}

/**
 * One reading of every [KeyPermission]. Shared by the permission watcher, the
 * Status page and the Welcome banner so "which permissions count" is defined
 * once.
 */
data class PermissionStatus(
    val usageAccess: Boolean,
    val notifications: Boolean,
    val batteryExemption: Boolean,
    val overlay: Boolean,
) {
    /** The permissions currently missing, always in [KeyPermission] declaration order. */
    val missing: Set<KeyPermission> = buildSet {
        if (!usageAccess) add(KeyPermission.USAGE_ACCESS)
        if (!notifications) add(KeyPermission.NOTIFICATIONS)
        if (!batteryExemption) add(KeyPermission.BATTERY_EXEMPTION)
        if (!overlay) add(KeyPermission.OVERLAY)
    }

    val isComplete: Boolean get() = missing.isEmpty()

    fun isGranted(permission: KeyPermission): Boolean = permission !in missing
}

fun PermissionChecker.status(): PermissionStatus = PermissionStatus(
    usageAccess = hasUsageAccess(),
    notifications = hasNotificationPermission(),
    batteryExemption = hasBatteryOptimizationExemption(),
    overlay = canDrawOverlays(),
)
