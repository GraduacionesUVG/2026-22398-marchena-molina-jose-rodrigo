package com.example.smartuse_frontend.jobs

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.RealPermissionChecker
import com.example.smartuse_frontend.data.RealSessionRepository
import com.example.smartuse_frontend.data.realLocalDataWiper
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import com.example.smartuse_frontend.data.db.runFullPipeline
import com.example.smartuse_frontend.inference.ProfileFreezer
import com.example.smartuse_frontend.inference.SqlProfileStore
import com.example.smartuse_frontend.inference.SqliteUsageSource
import com.example.smartuse_frontend.inference.capturedEpochs
import com.example.smartuse_frontend.finalsend.FinalSendRunner
import com.example.smartuse_frontend.forms.RealFormDraftStore
import com.example.smartuse_frontend.metrics.MetricsDbHelper
import com.example.smartuse_frontend.metrics.SqlMetricsStore
import com.example.smartuse_frontend.study.EndingCleanup
import com.example.smartuse_frontend.data.status
import com.example.smartuse_frontend.network.SmartUseApiImpl
import com.example.smartuse_frontend.notification.RealStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.service.RealStudyServiceController
import com.example.smartuse_frontend.service.ServiceMode
import com.example.smartuse_frontend.study.CutoffRefresher
import com.example.smartuse_frontend.study.StudyEnder
import com.example.smartuse_frontend.intervention.InterventionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

private const val TAG = "JobRuntime"

/** Assembles the real dependencies for a worker run (workers have no Activity/AppContainer). */
internal object JobRuntime {

    fun gate(context: Context): JobGate {
        val credentialStore = RealCredentialStore(context)
        val stateStore = RealJobStateStore(context)
        val session = RealSessionRepository(credentialStore, cutoffDate = { stateStore.cutoffDate() })
        return JobGate(
            hasSession = { credentialStore.hasCredentials() },
            isWindowOpen = { session.isCaptureWindowOpen() },
            isOptedOut = { stateStore.optedOutAt() != null },
            isCollectionEnded = { stateStore.collectionEndedAt() != null },
        )
    }

    suspend fun isStudyLive(context: Context): Boolean = gate(context).isStudyLive()

    /** The one decision every service start makes: launch, boot, the ender, the sticky restart. */
    suspend fun serviceMode(context: Context): ServiceMode =
        ServiceMode.of(RealJobStateStore(context).readEndMarks().state, isStudyLive(context))

    fun executor(context: Context, scheduler: BackgroundJobScheduler): JobExecutor {
        return JobExecutor(
            gate = gate(context),
            stateStore = RealJobStateStore(context),
            clock = Clock.systemUTC(),
            standbyBucket = { standbyBucket(context) },
            cancelAll = { scheduler.cancelAll() },
            onStudyEnded = { studyEnder(context, scheduler).end() },
            logger = { Log.d(TAG, it) },
        )
    }

    /** The one ending routine, with its real dependencies. Also used by the app (AppContainer). */
    fun studyEnder(context: Context, scheduler: BackgroundJobScheduler): StudyEnder = StudyEnder(
        stateStore = RealJobStateStore(context),
        cancelAll = { scheduler.cancelAll() },
        // The coordinator is main-thread only.
        stopInterventions = { withContext(Dispatchers.Main) { InterventionRuntime.coordinator(context).onStudyEnded() } },
        startService = { RealStudyServiceController(context).start() },
        notifier = RealStudyNotifier(context),
        clock = Clock.systemUTC(),
        logger = JobRuntime::log,
    )

    /** Moves the ending on and runs the steps after the confirmed send. */
    fun endingCleanup(context: Context): EndingCleanup = EndingCleanup(
        stateStore = RealJobStateStore(context),
        forms = RealFormDraftStore(context),
        ensureFinalSend = { BackgroundJobScheduler(context).ensureFinalSend() },
        wipeUsageData = { realLocalDataWiper(context).wipe() },
        stopService = { RealStudyServiceController(context).stop() },
        notifier = RealStudyNotifier(context),
        clock = Clock.systemUTC(),
        logger = JobRuntime::log,
    )

    fun finalSendRunner(context: Context): FinalSendRunner = FinalSendRunner(
        stateStore = RealJobStateStore(context),
        forms = RealFormDraftStore(context),
        readMetrics = { SqlMetricsStore(MetricsDbHelper(context)).readAll() },
        authToken = { RealCredentialStore(context).authToken() },
        api = SmartUseApiImpl(),
        cleanup = endingCleanup(context),
        clock = Clock.systemUTC(),
        random = Random.Default,
        logger = JobRuntime::log,
    )

    /** The profile freeze, the same for the cycle and the end of setup. */
    suspend fun profileFreezer(context: Context): ProfileFreezer {
        val canonical = CanonicalDbHelper(context)
        val signup = RealCredentialStore(context).signupTimestamp()
        return ProfileFreezer(
            store = SqlProfileStore(canonical),
            epochs = { capturedEpochs(SqliteUsageSource(canonical, RawCaptureDbHelper(context))) },
            offsetS = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds.toDouble() },
            forcedAt = signup?.plus(ProfileFreezer.FORCED_AFTER)?.epochSecond?.toDouble(),
            logger = ::log,
        )
    }

    /**
     * Setup's profile build from the accepted earlier history: capture (the cycle's
     * pipeline, under its lock), then one freeze attempt. A cycle freezing at the same
     * time loses its insert and logs it; the first freeze stands.
     */
    suspend fun buildProfileNow(context: Context) {
        runFullPipeline(context)
        profileFreezer(context).tryFreeze(Instant.now().epochSecond.toDouble())
    }

    fun cutoffRefresher(context: Context): CutoffRefresher =
        CutoffRefresher(api = SmartUseApiImpl(), stateStore = RealJobStateStore(context), logger = JobRuntime::log)

    fun log(message: String) {
        Log.d(TAG, message)
    }

    /** Posts "recording" with the current permissions and any announced cutoff; the capture job and the debug button. */
    /**
     * Every post of the study notification's text by mode (the service, the cycle,
     * the launch) reads the mode under this lock: a read from before the ending
     * never posts after the ending's own notice.
     */
    val noticeLock = Mutex()

    /** The recording notice, only while the study is live. */
    suspend fun refreshRecordingNotice(context: Context) = noticeLock.withLock {
        if (serviceMode(context) == ServiceMode.STUDY) postRecordingNotice(context)
    }

    /** The caller holds [noticeLock] and has read the mode as STUDY. */
    suspend fun postRecordingNotice(context: Context) {
        val stateStore = RealJobStateStore(context)
        val session = RealSessionRepository(RealCredentialStore(context), cutoffDate = { stateStore.cutoffDate() })
        RealStudyNotifier(context).show(
            StudyNotice.Recording(RealPermissionChecker(context).status().missing, session.announcedCutoff()),
        )
    }

    // Logged on every run: the OS demoting an idle app is the main risk to
    // the schedule.
    private fun standbyBucket(context: Context): Int? = runCatching {
        (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
    }.getOrNull()

    fun WorkDecision.toResult(): ListenableWorker.Result = when (this) {
        WorkDecision.SUCCESS -> ListenableWorker.Result.success()
        WorkDecision.RETRY -> ListenableWorker.Result.retry()
        WorkDecision.FAILURE -> ListenableWorker.Result.failure()
    }
}
