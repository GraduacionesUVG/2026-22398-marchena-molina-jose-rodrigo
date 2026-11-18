package com.example.smartuse_frontend.e2e

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/** Device actions through the instrumentation's shell (works on Test Lab without adb). */
object DeviceShell {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val pkg: String get() = instrumentation.targetContext.packageName

    fun run(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }.also { pfd.close() }
    }

    fun grantUsageAccess() = run("appops set $pkg GET_USAGE_STATS allow")
    fun revokeUsageAccess() = run("appops set $pkg GET_USAGE_STATS ignore")
    fun grantNotifications() = run("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    fun exemptFromBatteryOptimization() = run("cmd deviceidle whitelist +$pkg")

    fun wakeAndUnlock() {
        run("input keyevent KEYCODE_WAKEUP")
        run("wm dismiss-keyguard")
    }

    fun screenOff() = run("input keyevent KEYCODE_SLEEP")
    fun screenOn() = wakeAndUnlock()
    fun launchSettings() = run("am start -a android.settings.SETTINGS")
    fun goHome() = run("input keyevent KEYCODE_HOME")

    fun removeBatteryExemption() = run("cmd deviceidle whitelist -$pkg")

    /** active | working_set | frequent | rare | restricted */
    fun setStandbyBucket(name: String) = run("am set-standby-bucket $pkg $name")

    fun forceIdle() = run("dumpsys deviceidle force-idle")
    fun unforceIdle() = run("dumpsys deviceidle unforce")

    /** Loopback (the fake backend) keeps working; WorkManager sees no validated network. */
    fun setAirplaneMode(on: Boolean) = run("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
    fun airplaneModeOn(): Boolean = run("settings get global airplane_mode_on").trim() == "1"

    /**
     * The jobs JobScheduler holds for this app, asked in-process (the tests run in the
     * app's process, so its uid). WorkManager 2.10 files its jobs under the namespace
     * `androidx.work.systemjobscheduler` on API 34+, and the plain `allPendingJobs`
     * only lists the default namespace, so API 34+ asks across all namespaces.
     */
    fun scheduledJobs(): Set<ScheduledJob> {
        val scheduler = instrumentation.targetContext.getSystemService(JobScheduler::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scheduler.pendingJobsInAllNamespaces.flatMap { (namespace, jobs) -> jobs.map { it.toScheduledJob(namespace) } }.toSet()
        } else {
            scheduler.allPendingJobs.map { it.toScheduledJob(null) }.toSet()
        }
    }

    // WorkManager tags every job it hands to JobScheduler with the work's id and
    // generation (SystemJobInfoConverter), which is how a test matches the two.
    private fun JobInfo.toScheduledJob(namespace: String?) = ScheduledJob(
        namespace = namespace,
        id = id,
        workSpecId = extras.getString("EXTRA_WORK_SPEC_ID"),
        generation = extras.getInt("EXTRA_WORK_SPEC_GENERATION", 0),
    )

    /** Runs a scheduled job now, as the OS would when it's due; fails loudly if the device refuses. */
    fun forceRunJob(job: ScheduledJob) {
        val namespace = job.namespace?.let { "-n $it " }.orEmpty()
        val out = run("cmd jobscheduler run -f $namespace$pkg ${job.id}")
        // Success prints "Running job"; refusals ("Could not find job …", "Package not
        // found", "… has functional constraints") don't, and errors go to stderr.
        check(out.contains("Running job")) {
            "cmd jobscheduler refused $job on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}): $out"
        }
    }
}

/**
 * A job as JobScheduler knows it: its id is unique only within its namespace
 * (null = default); [workSpecId]/[generation] are WorkManager's tags on it.
 */
data class ScheduledJob(val namespace: String?, val id: Int, val workSpecId: String?, val generation: Int)
