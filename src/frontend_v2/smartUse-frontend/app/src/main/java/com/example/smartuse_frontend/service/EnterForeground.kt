package com.example.smartuse_frontend.service

/**
 * Puts the service in the foreground, or stops it if Android refuses. A sticky
 * restart from the background without the battery exemption throws
 * ForegroundServiceStartNotAllowedException (an IllegalStateException); left
 * uncaught it would crash the process in a restart loop. The next launch or
 * cycle starts the service again. Returns whether the service is in the foreground.
 */
fun enterForeground(startForeground: () -> Unit, stopSelf: () -> Unit, logger: (String) -> Unit): Boolean =
    try {
        startForeground()
        true
    } catch (e: IllegalStateException) {
        logger("foreground start refused: ${e::class.java.simpleName}: ${e.message}")
        stopSelf()
        false
    }
