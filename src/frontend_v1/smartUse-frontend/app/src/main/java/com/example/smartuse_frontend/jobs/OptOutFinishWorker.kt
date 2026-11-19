package com.example.smartuse_frontend.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.data.realLocalDataWiper
import com.example.smartuse_frontend.data.db.syncCanonicalToBackend
import com.example.smartuse_frontend.network.SmartUseApiImpl
import com.example.smartuse_frontend.notification.RealStudyNotifier

class OptOutFinishWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val stateStore = RealJobStateStore(context)
        val scheduler = BackgroundJobScheduler(context)
        val job = OptOutFinishJob(
            stateStore = stateStore,
            credentialStore = RealCredentialStore(context),
            api = SmartUseApiImpl(),
            runDrainCycle = { syncCanonicalToBackend(context) },
            cancelAll = { scheduler.cancelAll() },
            wipeLocalData = { realLocalDataWiper(context, stateStore).wipe() },
            notifier = RealStudyNotifier(context),
            logger = JobRuntime::log,
        )
        return if (job.run()) Result.success() else Result.retry()
    }
}
