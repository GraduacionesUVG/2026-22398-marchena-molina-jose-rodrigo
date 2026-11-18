package com.example.smartuse_frontend.intervention

import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.example.smartuse_frontend.data.RealPermissionChecker

private const val TAG = "Intervention"

/**
 * The one coordinator of the process, shared by the study service (screen
 * events), the decision points and the debug buttons.
 * Main thread only, which is also what keeps the lazy creation race-free.
 */
object InterventionRuntime {

    private var instance: InterventionCoordinator? = null

    fun coordinator(context: Context): InterventionCoordinator =
        instance ?: create(context.applicationContext).also { instance = it }

    private fun create(app: Context): InterventionCoordinator {
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val permissions = RealPermissionChecker(app)
        return InterventionCoordinator(
            // In use: screen on and unlocked.
            isInUse = { power.isInteractive && !keyguard.isKeyguardLocked },
            canDrawOverlays = permissions::canDrawOverlays,
            presenter = OverlayWindows(app),
            logger = { Log.d(TAG, it) },
            checkMainThread = { check(Looper.myLooper() == Looper.getMainLooper()) { "InterventionCoordinator used off the main thread" } },
        )
    }
}
