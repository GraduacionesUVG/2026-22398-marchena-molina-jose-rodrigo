package com.example.smartuse_frontend.service

import com.example.smartuse_frontend.inference.ModelRules
import com.example.smartuse_frontend.metrics.DecisionKind

/** A scheduled timer that can be called off. */
fun interface Cancellable {
    fun cancel()
}

private const val GAP_MS = (ModelRules.SESSION_GAP_S * 1000).toLong()
private const val K_MS = ModelRules.K_MIN * 60_000L
private const val T_MAX_MS = ModelRules.T_MAX_H * 3_600_000L

/**
 * When the decision points fall (models/extractor_features.md §4),
 * from the service's screen events (epoch ms). Only screen-on periods that get
 * unlocked count: a lock-screen glance has no apps, so training drops it before
 * joining sessions (§3.2), and here it neither starts a session nor moves the
 * 30 s gap. An unlocked period 30 s or more after the last unlocked one ended
 * starts a session at its screen-on, t0, and fires `screen_on` at t0; less than
 * that continues the session. Then `active_use` every 10 min from t0 while
 * the screen stays on, until t0 + 3 h; a point that falls while the screen is
 * off (or before the continuing period is unlocked) is skipped. If the service
 * starts mid-session (no screen-on seen) nothing fires until the next session.
 * Main thread only.
 */
class DecisionScheduler(
    private val schedule: (atMs: Long, action: () -> Unit) -> Cancellable,
    private val onPoint: (DecisionKind, tMs: Long) -> Unit,
) {
    /** End of the last unlocked screen-on period: the current session's end so far. */
    private var lastUseEnd: Long? = null
    private var t0: Long? = null

    /** The screen-on time of the period the screen is in now; null while off or never seen on. */
    private var periodStart: Long? = null
    private var periodUnlocked = false
    private var timer: Cancellable? = null

    fun onScreenOn(ts: Long) {
        periodStart = ts
        periodUnlocked = false
    }

    fun onUserPresent(ts: Long) {
        val start = periodStart ?: return
        if (periodUnlocked) return
        periodUnlocked = true
        val sessionStart = t0
        val end = lastUseEnd
        if (sessionStart != null && end != null && start - end < GAP_MS) {
            armAfter(ts)
        } else {
            t0 = start
            onPoint(DecisionKind.SCREEN_ON, start)
            arm(start + K_MS)
        }
    }

    fun onScreenOff(ts: Long) {
        cancelTimer()
        if (periodStart != null && periodUnlocked) lastUseEnd = ts
        periodStart = null
        periodUnlocked = false
    }

    fun stop() {
        cancelTimer()
        lastUseEnd = null
        t0 = null
        periodStart = null
        periodUnlocked = false
    }

    /** The next multiple of k after t0 that is later than [ts]. */
    private fun armAfter(ts: Long) {
        val start = t0 ?: return
        arm(start + ((ts - start) / K_MS + 1) * K_MS)
    }

    private fun arm(atMs: Long) {
        cancelTimer()
        val start = t0 ?: return
        if (atMs >= start + T_MAX_MS) return
        timer = schedule(atMs) {
            timer = null
            onPoint(DecisionKind.ACTIVE_USE, atMs)
            arm(atMs + K_MS)
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}
