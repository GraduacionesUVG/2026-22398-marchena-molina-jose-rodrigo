package com.example.smartuse_frontend.data

import java.time.LocalDate

interface SessionRepository {
    /** False from the end moment on: day 21 or the stored cutoff, whichever comes first. True before registration. */
    suspend fun isCaptureWindowOpen(): Boolean

    /** The cutoff to tell the participant about, or null (none, already passed, or not before their day 21). */
    suspend fun announcedCutoff(): LocalDate?
}
