package com.example.smartuse_frontend.data.db

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// One capture pipeline at a time per process: the capture job and the debug
// button both come through here. Each stage has its own cursor and is
// idempotent, so a run that dies halfway simply continues next time.
private val pipelineLock = Mutex()

// Runs both real pipeline stages back to back: project UsageStatsManager
// events into the raw capture log, then build canonical epochs from whatever's
// newly available there. No printing — use the dedicated print buttons to
// inspect the result afterward.
suspend fun runFullPipeline(context: Context) {
    pipelineLock.withLock {
        projectUsageEventsToRawLog(context)
        projectRawLogToCanonical(context)
    }
}
