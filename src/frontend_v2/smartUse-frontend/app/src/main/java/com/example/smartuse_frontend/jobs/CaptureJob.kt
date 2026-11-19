package com.example.smartuse_frontend.jobs

import com.example.smartuse_frontend.network.FailureKind
import kotlinx.coroutines.CancellationException

/**
 * Body of the capture job: refresh the study notification, then
 * UsageStatsManager -> raw log -> canonical epochs. Both stages have their own
 * cursor and are idempotent, so a run that dies partway simply continues next
 * time.
 */
class CaptureJob(
    private val refreshNotice: suspend () -> Unit,
    private val hasUsageAccess: () -> Boolean,
    private val runPipeline: suspend () -> Unit,
    private val logger: (String) -> Unit,
) {
    suspend fun body(): JobOutcome {
        // First, so a lapse reaches the notification even when this run then
        // fails for it. Capture runs are the notification's only re-post.
        try {
            refreshNotice()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("capture: notification refresh failed: ${e.message}")
        }
        if (!hasUsageAccess()) {
            logger("capture: usage access is not granted, failing permanently")
            return JobOutcome.Failed(FailureKind.PERMANENT, USAGE_ACCESS_REVOKED)
        }
        try {
            runPipeline()
        } catch (e: SecurityException) {
            logger("capture: SecurityException from the pipeline: ${e.message}")
            return JobOutcome.Failed(FailureKind.PERMANENT, "$USAGE_ACCESS_REVOKED (${e.message})")
        }
        return JobOutcome.Success()
    }

    companion object {
        const val USAGE_ACCESS_REVOKED = "usage access revoked"
    }
}
