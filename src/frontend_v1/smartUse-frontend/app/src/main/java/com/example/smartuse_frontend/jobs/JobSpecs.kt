package com.example.smartuse_frontend.jobs

enum class JobKind { CAPTURE, SYNC }

enum class SchedulePolicy { KEEP, UPDATE }

data class PeriodicJobSpec(
    val uniqueName: String,
    val kind: JobKind,
    val intervalMinutes: Long,
    val requiresNetwork: Boolean,
    val policy: SchedulePolicy,
)

data class OneShotJobSpec(
    val uniqueName: String,
    val kind: JobKind,
    val requiresNetwork: Boolean,
    val policy: SchedulePolicy,
)

/**
 * What gets scheduled, as plain data. Periodic works use UPDATE so a changed
 * interval (JobLimits) takes effect on the next launch; `sync-now` keeps a
 * pending run instead of stacking duplicates. Capture only ever enqueues
 * `sync-now`; sync never enqueues anything — there is no job cycle.
 */
object JobSpecs {
    const val CAPTURE_PERIODIC = "capture-periodic"
    const val SYNC_PERIODIC = "sync-periodic"
    const val SYNC_NOW = "sync-now"
    /** The removed 24h permission watcher; cancelled by name so old installs drop it. */
    const val LEGACY_PERMISSION_WATCH_PERIODIC = "permission-watch-periodic"

    val allNames = listOf(CAPTURE_PERIODIC, SYNC_PERIODIC, SYNC_NOW)

    fun periodic(limits: JobLimits): List<PeriodicJobSpec> = listOf(
        PeriodicJobSpec(
            uniqueName = CAPTURE_PERIODIC,
            kind = JobKind.CAPTURE,
            intervalMinutes = limits.effectiveCaptureIntervalMinutes,
            requiresNetwork = false,
            policy = SchedulePolicy.UPDATE,
        ),
        PeriodicJobSpec(
            uniqueName = SYNC_PERIODIC,
            kind = JobKind.SYNC,
            intervalMinutes = limits.effectiveSyncIntervalMinutes,
            requiresNetwork = true,
            policy = SchedulePolicy.UPDATE,
        ),
    )

    val syncNow = OneShotJobSpec(
        uniqueName = SYNC_NOW,
        kind = JobKind.SYNC,
        requiresNetwork = true,
        policy = SchedulePolicy.KEEP,
    )
}
