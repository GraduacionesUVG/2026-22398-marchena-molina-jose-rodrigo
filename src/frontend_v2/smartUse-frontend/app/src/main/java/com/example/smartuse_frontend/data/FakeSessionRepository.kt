package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.study.StudyWindow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class FakeSessionRepository(
    private val signupInstant: Instant = Instant.now().minus(3, ChronoUnit.DAYS),
    private val clock: Clock = Clock.systemUTC(),
    private val cutoff: LocalDate? = null,
) : SessionRepository {
    override suspend fun isCaptureWindowOpen(): Boolean =
        StudyWindow.isOpen(signupInstant, cutoff, Instant.now(clock))

    override suspend fun announcedCutoff(): LocalDate? =
        StudyWindow.announcedCutoff(signupInstant, cutoff, Instant.now(clock))
}
