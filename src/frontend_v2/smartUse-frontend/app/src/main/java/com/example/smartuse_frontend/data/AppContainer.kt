package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.notification.RealStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotifier
import android.content.Context
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.network.SmartUseApiImpl
import com.example.smartuse_frontend.service.RealStudyServiceController
import com.example.smartuse_frontend.service.StudyServiceController
import com.example.smartuse_frontend.forms.FormDraftStore
import com.example.smartuse_frontend.forms.FormsSubmitter
import com.example.smartuse_frontend.forms.RealFormDraftStore
import com.example.smartuse_frontend.service.ServiceMode
import com.example.smartuse_frontend.study.LaunchCheck
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.inference.Pattern
import com.example.smartuse_frontend.inference.SqlProfileStore
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.ui.welcome.InterventionsBox
import com.example.smartuse_frontend.ui.welcome.interventionsStartAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock

/** Manual DI holder. */
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

    /** The dashboard box: active once both profiles are frozen, else when interventions start at the latest. */
    val interventionsBox: suspend () -> InterventionsBox? = {
        withContext(Dispatchers.IO) {
            val profiles = SqlProfileStore(CanonicalDbHelper(appContext))
            val signup = credentialStore.signupTimestamp()
            val frozen = profiles.read(Pattern.F) != null && profiles.read(Pattern.S) != null
            when {
                frozen -> InterventionsBox.Active
                signup == null -> null
                else -> InterventionsBox.StartsBy(interventionsStartAt(signup, JobLimits.current.effectiveCycleIntervalMinutes))
            }
        }
    }

    // --- study notification ------------------------------------------------
    val studyNotifier: StudyNotifier = RealStudyNotifier(appContext)

    // --- study service -------------------------------------------------------
    val studyServiceController: StudyServiceController = RealStudyServiceController(appContext)

    // --- end of data collection ---------------------------------------------
    val launchCheck = LaunchCheck(
        refreshCutoff = { JobRuntime.cutoffRefresher(appContext).refresh() },
        isWindowOpen = { sessionRepository.isCaptureWindowOpen() },
        endCollection = { JobRuntime.studyEnder(appContext, jobScheduler).end() },
        ensureScheduled = { jobScheduler.ensureScheduled() },
        startService = { studyServiceController.start() },
        refreshNotice = { JobRuntime.refreshRecordingNotice(appContext) },
    )

    // --- end forms and the ending ---------------------------------------------
    val formDraftStore: FormDraftStore = RealFormDraftStore(appContext)
    val formsSubmitter = FormsSubmitter(
        drafts = formDraftStore,
        stateStore = jobStateStore,
        ensureFinalSend = { jobScheduler.ensureFinalSend() },
        clock = Clock.systemUTC(),
    )

    /** A launch after the study ended: move the ending on, and keep the service in its mode. */
    suspend fun resumeEnding() {
        JobRuntime.endingCleanup(appContext).ensure()
        if (JobRuntime.serviceMode(appContext) != ServiceMode.OFF) studyServiceController.start()
    }

    // --- opt-out -------------------------------------------------------------
    val optOutRepository: OptOutRepository = RealOptOutRepository(
        endStudy = { JobRuntime.studyEnder(appContext, jobScheduler).end(it) },
    )
}
