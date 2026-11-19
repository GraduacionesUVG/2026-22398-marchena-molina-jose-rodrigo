package com.example.smartuse_frontend.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "StudyService"

/** Starts and stops the study service. Implementations never throw. */
interface StudyServiceController {
    /** Idempotent: starting a running service only re-posts the same notification. */
    fun start()
    fun stop()
}

class RealStudyServiceController(
    context: Context,
    private val logger: (String) -> Unit = { Log.d(TAG, it) },
) : StudyServiceController {
    private val appContext = context.applicationContext

    private fun intent() = Intent(appContext, StudyService::class.java)

    override fun start() {
        try {
            ContextCompat.startForegroundService(appContext, intent())
        } catch (e: Exception) {
            // From the background without the battery exemption this is
            // ForegroundServiceStartNotAllowedException; the next launch starts it.
            logger("could not start the study service: ${e::class.java.simpleName}: ${e.message}")
        }
    }

    override fun stop() {
        try {
            appContext.stopService(intent())
        } catch (e: Exception) {
            logger("could not stop the study service: ${e::class.java.simpleName}: ${e.message}")
        }
    }
}

class FakeStudyServiceController : StudyServiceController {
    var starts = 0
        private set
    var stops = 0
        private set

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}
