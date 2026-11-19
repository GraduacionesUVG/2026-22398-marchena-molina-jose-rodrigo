package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.notification.RealStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotifier
import android.content.Context
import com.example.smartuse_frontend.data.db.syncCanonicalToBackend
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.network.SmartUseApiImpl
import com.example.smartuse_frontend.study.LaunchCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/**
 * Manual DI holder: the app's real repositories, stores and API client.
 * Tests build their own fakes instead.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // --- registration flow (real) --------------------------------------
    val credentialStore: CredentialStore = RealCredentialStore(appContext)
    val keystoreKeyManager: KeystoreKeyManager = RealKeystoreKeyManager()
    val setupStateRepository: SetupStateRepository = RealSetupStateRepository(appContext)
    val permissionChecker: PermissionChecker = RealPermissionChecker(appContext)
    val registrationRepository: RegistrationRepository =
        RealRegistrationRepository(SmartUseApiImpl())

    // --- background jobs ---------------------------------------------------
    val jobStateStore: JobStateStore = RealJobStateStore(appContext)
    val jobScheduler = BackgroundJobScheduler(appContext)

    // --- post-setup pages --------------------------------------------------
    val sessionRepository: SessionRepository =
        RealSessionRepository(credentialStore, cutoffDate = { jobStateStore.cutoffDate() })
    val profileRepository: ProfileRepository = RealProfileRepository(credentialStore)
    val usageStatsRepository: UsageStatsRepository = RealUsageStatsRepository(appContext)
    val syncCursorStore: SyncCursorStore = RealSyncCursorStore(appContext)
    val syncStatusRepository: SyncStatusRepository = RealSyncStatusRepository(syncCursorStore)

    // --- study notification ------------------------------------------------
    val studyNotifier: StudyNotifier = RealStudyNotifier(appContext)

    // --- end of data collection ---------------------------------------------
    val launchCheck = LaunchCheck(
        refreshCutoff = { JobRuntime.cutoffRefresher(appContext).refresh() },
        isWindowOpen = { sessionRepository.isCaptureWindowOpen() },
        endCollection = { JobRuntime.collectionEnder(appContext, jobScheduler).end() },
        ensureScheduled = { jobScheduler.ensureScheduled() },
        refreshNotice = { JobRuntime.refreshRecordingNotice(appContext) },
    )

    // --- opt-out -------------------------------------------------------------
    // Long-lived: outlives any single opt-out screen, so a soft drain started
    // there survives the screen being left.
    private val optOutBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val optOutRepository: OptOutRepository = RealOptOutRepository(
        stateStore = jobStateStore,
        credentialStore = credentialStore,
        api = SmartUseApiImpl(),
        cancelCapture = { jobScheduler.cancelCapture() },
        cancelAll = { jobScheduler.cancelAll() },
        runDrainCycle = { syncCanonicalToBackend(appContext) },
        scheduleFinishWorker = { jobScheduler.enqueueOptOutFinish() },
        wipeLocalData = { realLocalDataWiper(appContext, jobStateStore).wipe() },
        notifier = studyNotifier,
        backgroundScope = optOutBackgroundScope,
        clock = Clock.systemUTC(),
        refreshCutoff = { JobRuntime.cutoffRefresher(appContext).refresh() },
        isWindowOpen = { sessionRepository.isCaptureWindowOpen() },
        endCollection = { JobRuntime.collectionEnder(appContext, jobScheduler).end() },
    )
}
