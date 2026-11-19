package com.example.smartuse_frontend.e2e

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Real screen and app events, the way a participant produces them. */
object Usage {
    /** Screen-off and screen-on events since [since] that UsageStatsManager already returns (needs usage access). */
    fun screenEventsSince(context: Context, since: Long): Pair<Int, Int> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(since, System.currentTimeMillis())
        val e = UsageEvents.Event()
        var offs = 0
        var ons = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> offs++
                UsageEvents.Event.SCREEN_INTERACTIVE -> ons++
            }
        }
        return offs to ons
    }

    /** Waits until 2 screen-offs and 2 screen-ons since [since] are queryable (later events can lag earlier ones). */
    fun awaitScreenEvents(context: Context, since: Long) {
        await("2 screen-offs and 2 screen-ons queryable in UsageStatsManager", 30_000) {
            screenEventsSince(context, since).takeIf { (offs, ons) -> offs >= 2 && ons >= 2 }
        }
    }

    /**
     * Screen on → two apps → off → on → off → on: several closed epochs with successors.
     * [verify] = false while usage access is revoked: the OS still records the
     * events, the app just can't read them (call [awaitScreenEvents] after restoring).
     */
    fun generate(context: Context, verify: Boolean = true) {
        val start = System.currentTimeMillis()
        DeviceShell.screenOn(); Thread.sleep(2_000)
        DeviceShell.launchSettings(); Thread.sleep(2_000)
        DeviceShell.goHome(); Thread.sleep(2_000)
        DeviceShell.screenOff(); Thread.sleep(3_000)
        DeviceShell.screenOn(); Thread.sleep(3_000)
        DeviceShell.screenOff(); Thread.sleep(3_000)
        DeviceShell.screenOn(); Thread.sleep(2_000)
        if (verify) awaitScreenEvents(context, start)
    }
}
