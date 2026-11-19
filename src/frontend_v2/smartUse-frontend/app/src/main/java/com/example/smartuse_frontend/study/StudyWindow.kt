package com.example.smartuse_frontend.study

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When data collection ends: 21 days after signup or the study-wide cutoff,
 * whichever comes first. Pure; the device clock is trusted.
 */
object StudyWindow {
    /** A fixed zone, so a phone set to another zone doesn't shift the cutoff. */
    val CUTOFF_ZONE: ZoneId = ZoneId.of("America/Guatemala")
    val CAPTURE_WINDOW: Duration = Duration.ofDays(21)

    /** The cutoff date is the first day with no collection: it starts at 00:00 Guatemala. */
    fun cutoffMoment(cutoff: LocalDate): Instant = cutoff.atStartOfDay(CUTOFF_ZONE).toInstant()

    fun endMoment(signupTs: Instant, cutoff: LocalDate?): Instant {
        val day21 = signupTs.plus(CAPTURE_WINDOW)
        return if (cutoff == null) day21 else minOf(day21, cutoffMoment(cutoff))
    }

    fun isOpen(signupTs: Instant, cutoff: LocalDate?, now: Instant): Boolean = now < endMoment(signupTs, cutoff)

    /** The cutoff, only while it is still ahead and ends this participant's collection before day 21. */
    fun announcedCutoff(signupTs: Instant, cutoff: LocalDate?, now: Instant): LocalDate? {
        if (cutoff == null) return null
        val moment = cutoffMoment(cutoff)
        return cutoff.takeIf { moment > now && moment < signupTs.plus(CAPTURE_WINDOW) }
    }
}
