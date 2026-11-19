package com.example.smartuse_frontend.jobs

enum class JobKind { CYCLE }

enum class SchedulePolicy { KEEP, UPDATE }

data class PeriodicJobSpec(
    val uniqueName: String,
    val kind: JobKind,
    val intervalMinutes: Long,
    val requiresNetwork: Boolean,
    val policy: SchedulePolicy,
)

/**
 * What gets scheduled, as plain data. Periodic works use UPDATE so a changed
 * interval (JobLimits) takes effect on the next launch.
 */
object JobSpecs {
    /** Capture, then inference, every `t` (CycleWorker). */
    const val CYCLE_PERIODIC = "cycle-periodic"

    /** The final send (FinalSendWorker): one-time unique work, never in [allNames], so cancelAll() leaves it. */
    const val FINAL_SEND = "final-send"

    /** Scheduled by earlier builds; cancelled so an updated install never keeps two schedules. */
    const val RETIRED_CAPTURE_PERIODIC = "capture-periodic"

    val retiredNames = listOf(RETIRED_CAPTURE_PERIODIC)

    val allNames = listOf(CYCLE_PERIODIC) + retiredNames

    fun periodic(limits: JobLimits): List<PeriodicJobSpec> = listOf(
        PeriodicJobSpec(
            uniqueName = CYCLE_PERIODIC,
            kind = JobKind.CYCLE,
            intervalMinutes = limits.effectiveCycleIntervalMinutes,
            requiresNetwork = false,
            policy = SchedulePolicy.UPDATE,
        ),
    )
}
