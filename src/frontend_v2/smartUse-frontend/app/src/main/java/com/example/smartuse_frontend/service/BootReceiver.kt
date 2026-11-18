package com.example.smartuse_frontend.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "BootReceiver"

/**
 * Exported only because BOOT_COMPLETED requires it; any other action (an
 * explicit broadcast from another app) is ignored. Starting a foreground
 * service from BOOT_COMPLETED is allowed at targetSdk 33.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                BootStart(
                    serviceMode = { JobRuntime.serviceMode(appContext) },
                    startService = { RealStudyServiceController(appContext).start() },
                    ensureScheduled = { BackgroundJobScheduler(appContext).ensureScheduled() },
                    ensureEnding = { JobRuntime.endingCleanup(appContext).ensure() },
                ).run()
            } catch (e: Exception) {
                Log.w(TAG, "boot start failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
