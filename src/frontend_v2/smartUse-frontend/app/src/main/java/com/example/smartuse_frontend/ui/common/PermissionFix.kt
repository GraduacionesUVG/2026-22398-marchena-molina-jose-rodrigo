package com.example.smartuse_frontend.ui.common

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import com.example.smartuse_frontend.data.KeyPermission

/** One system screen that can fix a permission; [toIntent] turns it into an [Intent]. */
data class FixTarget(
    val action: String,
    val withPackageUri: Boolean = false,
    val withPackageExtra: Boolean = false,
)

/**
 * Where to send the participant to turn a permission back on: an exact target
 * first (this app's own page or dialog), then a broader fallback for the
 * manufacturers whose settings app does not honour the exact one. The
 * fallbacks make sure the fix link always opens something.
 */
object PermissionFixTargets {

    // BatteryLife flags ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS for Play Store
    // policy; the APK is distributed manually (see the manifest note), so it does
    // not apply.
    @SuppressLint("BatteryLife")
    fun forPermission(permission: KeyPermission): List<FixTarget> = when (permission) {
        KeyPermission.USAGE_ACCESS -> listOf(
            FixTarget(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackageUri = true),
            FixTarget(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
        KeyPermission.NOTIFICATIONS -> listOf(
            FixTarget(Settings.ACTION_APP_NOTIFICATION_SETTINGS, withPackageExtra = true),
            FixTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackageUri = true),
        )
        KeyPermission.BATTERY_EXEMPTION -> listOf(
            FixTarget(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackageUri = true),
            FixTarget(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        KeyPermission.OVERLAY -> listOf(
            FixTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackageUri = true),
            FixTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )
    }
}

fun FixTarget.toIntent(packageName: String): Intent = Intent(action).apply {
    if (withPackageUri) data = "package:$packageName".toUri()
    if (withPackageExtra) putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
}

/**
 * Opens the first target that starts. `resolveActivity` is unreliable under
 * package-visibility rules, so failing to start is the check: a missing handler
 * or a refused intent moves on to the next target. Returns false only if none
 * of them could be opened.
 */
fun Context.openPermissionFix(permission: KeyPermission): Boolean {
    for (target in PermissionFixTargets.forPermission(permission)) {
        try {
            startActivity(target.toIntent(packageName))
            return true
        } catch (e: ActivityNotFoundException) {
            // no handler for this target: try the next, broader one
        } catch (e: SecurityException) {
            // the target refused the intent: try the next, broader one
        }
    }
    return false
}
