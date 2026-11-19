package com.example.smartuse_frontend.jobs

import kotlinx.coroutines.CancellationException

/**
 * One run of the cycle, every `t`: make sure the service is up (only while the
 * study is live, so a cycle never revives it after the ending), capture, then
 * try to freeze the model profiles. The cycle's result is
 * capture's: the profile freeze never changes it, and inference runs at the
 * decision points, not here.
 */
class StudyCycle(
    private val isStudyLive: suspend () -> Boolean,
    private val startService: () -> Unit,
    private val runCapture: suspend () -> WorkDecision,
    private val freezeProfiles: suspend () -> Unit,
    private val logger: (String) -> Unit,
) {
    suspend fun run(): WorkDecision {
        if (isStudyLive()) startService()
        val capture = runCapture()
        try {
            freezeProfiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("cycle: profile freeze failed: ${e::class.java.simpleName}: ${e.message}")
        }
        logger("cycle: capture=$capture")
        return capture
    }
}
