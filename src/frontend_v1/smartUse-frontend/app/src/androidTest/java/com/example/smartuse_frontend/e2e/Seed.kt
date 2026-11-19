package com.example.smartuse_frontend.e2e

import android.content.Context
import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.RealKeystoreKeyManager
import com.example.smartuse_frontend.data.RealSetupStateRepository
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.RealJobStateStore
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Writes, through the app's real stores, what a finished setup would have written. */
object Seed {
    const val QA_MAJOR = "QA-BACKEND-CHECK"

    fun registeredSession(context: Context, token: String, signupHoursAgo: Long = 3) = runBlocking {
        RealCredentialStore(context).saveSession(
            PersistedSession(
                userId = "u-e2e",
                authToken = token,
                group = ExperimentGroup.CONTROL,
                signupTs = Instant.now().minus(signupHoursAgo, ChronoUnit.HOURS),
                profile = ParticipantProfile(age = 21, gender = "Femenino", universityMajor = QA_MAJOR, sasSvScore = 0),
            ),
        )
        RealKeystoreKeyManager().ensureHmacKey()
        RealSetupStateRepository(context).markComplete()
    }

    /**
     * A registered participant with every permission, the jobs scheduled and
     * their own first runs finished (WorkManager runs a new periodic job at once).
     */
    fun collecting(context: Context, backend: FakeBackend) {
        registeredSession(context, backend.token)
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        DeviceShell.exemptFromBatteryOptimization()
        val scheduledAt = Instant.now()
        BackgroundJobScheduler(context).ensureScheduled()
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, scheduledAt)
        WorkJobs.awaitQuiet(context)
    }

    fun storedCutoff(context: Context, date: LocalDate) = runBlocking {
        RealJobStateStore(context).setCutoffDate(date)
    }

    /** Creates both local databases (empty), so a test can prove they get deleted. */
    fun canonicalAndRawDatabases(context: Context) {
        CanonicalDbHelper(context).writableDatabase.close()
        RawCaptureDbHelper(context).writableDatabase.close()
    }
}
