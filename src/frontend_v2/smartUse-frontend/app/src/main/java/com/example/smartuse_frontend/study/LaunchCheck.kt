package com.example.smartuse_frontend.study

/**
 * What a fresh launch does once setup is done (and the install is neither
 * opted out nor ended). Passive: it decides from the stored date first and
 * re-schedules the jobs straight away; the fetch runs afterwards and nothing
 * waits on it. A newly learned date either ends collection or re-posts the
 * notification with the announcement. The study service starts only once the
 * fetched date keeps the window open: stopping a service started with
 * startForegroundService() before it reaches startForeground() crashes the app.
 */
class LaunchCheck(
    private val refreshCutoff: suspend () -> Boolean,
    private val isWindowOpen: suspend () -> Boolean,
    private val endCollection: suspend () -> Unit,
    private val ensureScheduled: () -> Unit,
    private val startService: () -> Unit,
    private val refreshNotice: suspend () -> Unit,
) {
    suspend fun run() {
        if (!isWindowOpen()) {
            endCollection()
            return
        }
        ensureScheduled()
        val changed = refreshCutoff()
        if (!isWindowOpen()) {
            endCollection()
            return
        }
        startService()
        if (changed) refreshNotice()
    }
}
