package com.example.smartuse_frontend.jobs

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.RealPermissionChecker
import com.example.smartuse_frontend.data.RealSessionRepository
import com.example.smartuse_frontend.data.realLocalDataWiper
import com.example.smartuse_frontend.data.status
import com.example.smartuse_frontend.network.SmartUseApiImpl
import com.example.smartuse_frontend.notification.RealStudyNotifier
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.study.CollectionEnder
import com.example.smartuse_frontend.study.CutoffRefresher
import java.time.Clock

private const val TAG = "JobRuntime"

/** Assembles the real dependencies for a worker run (workers have no Activity/AppContainer). */
internal object JobRuntime {

    fun executor(context: Context, scheduler: BackgroundJobScheduler): JobExecutor {
        val credentialStore = RealCredentialStore(context)
        val stateStore = RealJobStateStore(context)
        val session = RealSessionRepository(credentialStore, cutoffDate = { stateStore.cutoffDate() })
        return JobExecutor(
            gate = JobGate(
                hasSession = { credentialStore.hasCredentials() },
                isWindowOpen = { session.isCaptureWindowOpen() },
                isOptedOut = { stateStore.optedOutAt() != null },
                isCollectionEnded = { stateStore.collectionEndedAt() != null },
            ),
            stateStore = stateStore,
            clock = Clock.systemUTC(),
            standbyBucket = { standbyBucket(context) },
            cancelAll = { scheduler.cancelAll() },
            onStudyEnded = { collectionEnder(context, scheduler).end() },
            logger = { Log.d(TAG, it) },
        )
    }

    /** The one ending routine, with its real dependencies. Also used by the app (AppContainer). */
    fun collectionEnder(context: Context, scheduler: BackgroundJobScheduler): CollectionEnder {
        val stateStore = RealJobStateStore(context)
        return CollectionEnder(
            stateStore = stateStore,
            cancelAll = { scheduler.cancelAll() },
            wipeLocalData = { realLocalDataWiper(context, stateStore).wipe() },
            notifier = RealStudyNotifier(context),
            clock = Clock.systemUTC(),
            logger = JobRuntime::log,
        )
    }

    fun cutoffRefresher(context: Context): CutoffRefresher =
        CutoffRefresher(api = SmartUseApiImpl(), stateStore = RealJobStateStore(context), logger = JobRuntime::log)

    fun log(message: String) {
        Log.d(TAG, message)
    }

    /** Posts "recording" with the current permissions and any announced cutoff; the capture job and the debug button. */
    suspend fun refreshRecordingNotice(context: Context) {
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
