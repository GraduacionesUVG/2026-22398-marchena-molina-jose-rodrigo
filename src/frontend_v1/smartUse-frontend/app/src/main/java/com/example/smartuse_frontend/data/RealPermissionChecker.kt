package com.example.smartuse_frontend.data

import android.app.AppOpsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat

class RealPermissionChecker(private val context: Context) : PermissionChecker {

    override fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // On API 33+ this is false until POST_NOTIFICATIONS is granted, and also
    // false if the participant later switches notifications off for the app.
    override fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun hasBatteryOptimizationExemption(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
