package com.example.smartuse_frontend.e2e

import android.content.Context
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.PersistedSession
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.RealKeystoreKeyManager
import com.example.smartuse_frontend.data.RealSetupStateRepository
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import com.example.smartuse_frontend.forms.FormAnswer
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.forms.QuestionKind
import com.example.smartuse_frontend.forms.RealFormDraftStore
import com.example.smartuse_frontend.inference.Pattern
import com.example.smartuse_frontend.inference.Profile
import com.example.smartuse_frontend.inference.SqlProfileStore
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.metrics.DecisionKind
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.metrics.MetricsDbHelper
import com.example.smartuse_frontend.metrics.SqlMetricsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Writes, through the app's real stores, what a finished setup would have written. */
object Seed {
    const val QA_MAJOR = "QA-BACKEND-CHECK"

    fun registeredSession(context: Context, token: String, signupHoursAgo: Long = 3, userId: String = "u-e2e") = runBlocking {
        RealCredentialStore(context).saveSession(
            PersistedSession(
                userId = userId,
                authToken = token,
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
        DeviceShell.grantOverlay()
        val scheduledAt = Instant.now()
        BackgroundJobScheduler(context).ensureScheduled()
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, scheduledAt)
        WorkJobs.awaitQuiet(context)
    }

    fun storedCutoff(context: Context, date: LocalDate) = runBlocking {
        RealJobStateStore(context).setCutoffDate(date)
    }

    /** A set-up participant whose study has ended (day 21, or the opt-out), waiting for the end forms. */
    fun endedStudy(context: Context, token: String, optedOut: Boolean = false) = runBlocking {
        registeredSession(context, token)
        val store = RealJobStateStore(context)
        if (optedOut) store.setOptedOutAt(Instant.now()) else store.setCollectionEndedAt(Instant.now())
    }

    /** Every required end-form answer, drafted: each choice question's first option. */
    fun filledEndForms(context: Context) = runBlocking {
        val drafts = RealFormDraftStore(context)
        FormDefinitions.END_FORMS.forEach { form ->
            form.questions.filter { it.required }.forEach {
                val first = (it.kind as QuestionKind.SingleChoice).options.first().value
                drafts.saveAnswer(form.id, it.id, FormAnswer.Choice(first))
            }
        }
    }

    /** [n] performance records, as live cycles would have stored them. */
    fun metrics(context: Context, n: Int) {
        val store = SqlMetricsStore(MetricsDbHelper(context))
        repeat(n) { store.insert(DecisionMetric(DecisionKind.ACTIVE_USE, 20L + it, null, null, null)) }
    }

    /** The fixture person's two profiles, frozen as the cycle would have (decision points run from here on). */
    fun frozenProfiles(context: Context, person: String = FixtureAssets.PERSON) {
        val json = Json { ignoreUnknownKeys = true }
        fun profile(pattern: Pattern) = json.decodeFromString(Profile.serializer(), FixtureAssets.text(person, "profile_${pattern.name}.json"))
        SqlProfileStore(CanonicalDbHelper(context)).freeze(profile(Pattern.F), profile(Pattern.S))
    }

    /** Creates both local databases (empty), so a test can prove they get deleted. */
    fun canonicalAndRawDatabases(context: Context) {
        CanonicalDbHelper(context).writableDatabase.close()
        RawCaptureDbHelper(context).writableDatabase.close()
    }
}
