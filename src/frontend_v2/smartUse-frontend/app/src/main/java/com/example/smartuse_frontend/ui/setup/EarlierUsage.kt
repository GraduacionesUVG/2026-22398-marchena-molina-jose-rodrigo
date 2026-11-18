package com.example.smartuse_frontend.ui.setup

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.smartuse_frontend.data.isCaptured
import java.time.Instant
import java.time.ZoneId

/**
 * The earlier-usage window: the 3 full local days before
 * the consent day. Capture starts at [historyStart] when the participant
 * accepts; [windowEnd] only bounds the check for whether there is anything to ask about.
 */
fun historyStart(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().minusDays(3).atStartOfDay(zone).toInstant()

/** 00:00 of the consent day. */
fun windowEnd(now: Instant, zone: ZoneId): Instant = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

/** A usage event as the check reads it. */
data class RawEvent(val ts: Long, val type: Int, val pkg: String?)

/** Whether the window holds an event capture would keep (SmartUse's own screens excluded). */
fun hasEarlierUsage(events: Sequence<RawEvent>, start: Instant, end: Instant, ownPackage: String): Boolean =
    events.any { it.ts >= start.toEpochMilli() && it.ts < end.toEpochMilli() && isCaptured(it.type, it.pkg, ownPackage) }

/** The check on the device's own UsageStatsManager; throws without usage access (the step is then skipped). */
fun usmHasEarlierUsage(context: Context, start: Instant, end: Instant): Boolean {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val events = usm.queryEvents(start.toEpochMilli(), end.toEpochMilli())
    val event = UsageEvents.Event()
    val read = generateSequence {
        if (!events.hasNextEvent()) null else { events.getNextEvent(event); RawEvent(event.timeStamp, event.eventType, event.packageName) }
    }
    return hasEarlierUsage(read, start, end, context.packageName)
}
