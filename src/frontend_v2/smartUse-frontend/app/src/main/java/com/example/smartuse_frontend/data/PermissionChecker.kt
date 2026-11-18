package com.example.smartuse_frontend.data

/** Live OS-permission checks used by setup and (later) the Status page. */
interface PermissionChecker {
    /** Whether PACKAGE_USAGE_STATS (usage access) is currently granted to this app. */
    fun hasUsageAccess(): Boolean

    /**
     * Whether this app's notifications can currently be shown — POST_NOTIFICATIONS
     * granted (a runtime permission from API 33, our minSdk) and not switched off
     * in the app's system notification settings. Without it the foreground
     * service still runs, but its notification is hidden from the drawer.
     */
    fun hasNotificationPermission(): Boolean

    /**
     * Whether this app is on the battery-optimization allowlist. Being on it keeps
     * the OS from demoting an idle app to a restrictive standby bucket, which is
     * what would throttle the background jobs.
     */
    fun hasBatteryOptimizationExemption(): Boolean

    /**
     * Whether this app may draw over other apps (SYSTEM_ALERT_WINDOW). Without it
     * the intervention overlay can't be shown; the study otherwise runs.
     */
    fun canDrawOverlays(): Boolean
}
