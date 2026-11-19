package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.data.db.RawEvent
import com.example.smartuse_frontend.data.db.buildEpochs

private val SCREEN = setOf("SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE")

/** Raw rows as captured; canonical epochs = what capture would have built from them. */
internal class FakeUsageSource(private val raw: List<RawEvent>) : UsageSource {
    private val built = buildEpochs(raw, 0)
    private val epochs = built.epochs

    override fun frontier(): Long? =
        raw.filter { it.id > built.nextCursor && it.eventType in SCREEN }.minByOrNull { it.id }?.eventTs

    override fun lastScreenEventTs(atOrBefore: Long): Long? =
        raw.filter { it.eventType in SCREEN && it.eventTs <= atOrBefore }.maxOfOrNull { it.eventTs }

    override fun hasScreenEventAtOrAfter(ts: Long): Boolean = raw.any { it.eventType in SCREEN && it.eventTs >= ts }

    override fun rawEvents(from: Long, to: Long): List<RawEvent> =
        raw.filter { it.eventTs >= from && it.eventTs < to }.sortedWith(compareBy({ it.eventTs }, { it.id }))

    override fun closedEpochs(from: Long, end: Long): List<SeriesEpoch> {
        val starts = epochs.filter { it.startTs < end }
        val firstIndex = starts.indexOfLast { it.startTs <= from }.coerceAtLeast(0)
        return starts.drop(firstIndex).mapIndexed { i, e ->
            val next = starts.getOrNull(firstIndex + i + 1)?.startTs ?: end
            SeriesEpoch(e.startTs, next, e.isUse, e.appHashes.size)
        }
    }

    override fun firstEpochStart(): Long? = epochs.firstOrNull()?.startTs
}

/** Builds raw rows with increasing ids, in the order they are listed. */
internal class RawLog {
    private var id = 1L
    val rows = mutableListOf<RawEvent>()
    fun on(ts: Long) = apply { rows += RawEvent(id++, ts, "SCREEN_INTERACTIVE", null) }
    fun off(ts: Long) = apply { rows += RawEvent(id++, ts, "SCREEN_NON_INTERACTIVE", null) }
    fun app(ts: Long, hash: String) = apply { rows += RawEvent(id++, ts, "ACTIVITY_RESUMED", hash) }
}
