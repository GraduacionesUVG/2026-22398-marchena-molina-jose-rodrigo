package com.example.smartuse_frontend.inference

/** A screen transition the study service saw (epoch ms, on = screen on). */
data class ScreenObservation(val ts: Long, val on: Boolean)

/** What the service saw: since when it has been observing (null = not now) and the transitions. */
data class ObservationSnapshot(val observingSince: Long?, val observations: List<ScreenObservation>) {
    companion object {
        val NONE = ObservationSnapshot(null, emptyList())
    }
}

/**
 * The service's own record of screen on/off, for the part of the tail USM has
 * not published yet. In memory only: it dies with the
 * process, which is exactly "not observing". Written on the main thread,
 * read by the cycle's thread, hence synchronised.
 */
class ScreenObservationLog(private val cap: Int = 1_000) {
    private var observingSince: Long? = null
    private val observations = ArrayDeque<ScreenObservation>()

    @Synchronized
    fun startObserving(now: Long, screenOn: Boolean) {
        observations.clear()
        observingSince = now
        observations.addLast(ScreenObservation(now, screenOn))
    }

    @Synchronized
    fun record(ts: Long, on: Boolean) {
        if (observingSince == null) return
        observations.addLast(ScreenObservation(ts, on))
        while (observations.size > cap) observations.removeFirst()
    }

    @Synchronized
    fun stopObserving() {
        observingSince = null
        observations.clear()
    }

    /** USM has published everything up to [ts]: older observations are no longer needed. */
    @Synchronized
    fun pruneAtOrBefore(ts: Long) {
        while (observations.firstOrNull()?.let { it.ts <= ts } == true) observations.removeFirst()
    }

    @Synchronized
    fun snapshot(): ObservationSnapshot = ObservationSnapshot(observingSince, observations.toList())
}

/** The process's one log: StudyService writes it, the cycle reads it. */
object ScreenObservations {
    val log = ScreenObservationLog()
}
