package com.example.smartuse_frontend.ui.setup

/** One place the "Abrir ajustes" button can try to open. */
sealed interface AutostartTarget {
    data class Screen(val packageName: String, val className: String) : AutostartTarget

    /** The app's own system settings page: the fallback that always exists. */
    object AppDetails : AutostartTarget
}

/**
 * Brands that stop background apps beyond stock Android, and their known
 * autostart / battery screens (best-known component names, unverified per
 * device). Android has no API to read whether autostart is on, so the setup
 * step can only open the screen.
 */
object AutostartSettings {

    private val XIAOMI = listOf(
        AutostartTarget.Screen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )
    private val HUAWEI = listOf(
        AutostartTarget.Screen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        AutostartTarget.Screen("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )
    private val HONOR = listOf(
        AutostartTarget.Screen("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    )
    private val OPPO = listOf(
        AutostartTarget.Screen("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        AutostartTarget.Screen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        AutostartTarget.Screen("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
    private val ONEPLUS = listOf(
        AutostartTarget.Screen("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    )
    private val VIVO = listOf(
        AutostartTarget.Screen("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        AutostartTarget.Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    private val SAMSUNG = listOf(
        AutostartTarget.Screen("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )
    private val ASUS = listOf(
        AutostartTarget.Screen("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
    )
    private val TRANSSION = listOf(
        AutostartTarget.Screen("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
    )

    // Build.MANUFACTURER, lowercased.
    private val SCREENS: Map<String, List<AutostartTarget.Screen>> = mapOf(
        "xiaomi" to XIAOMI, "redmi" to XIAOMI, "poco" to XIAOMI,
        "huawei" to HUAWEI,
        "honor" to HONOR,
        "oppo" to OPPO, "realme" to OPPO,
        "oneplus" to ONEPLUS,
        "vivo" to VIVO, "iqoo" to VIVO,
        "samsung" to SAMSUNG,
        "asus" to ASUS,
        "tecno" to TRANSSION, "infinix" to TRANSSION, "itel" to TRANSSION,
    )

    fun needsAutostartStep(manufacturer: String): Boolean = manufacturer.trim().lowercase() in SCREENS

    /** The brand's screens in order, then the app's own settings page. */
    fun targets(manufacturer: String): List<AutostartTarget> =
        SCREENS[manufacturer.trim().lowercase()].orEmpty() + AutostartTarget.AppDetails
}
