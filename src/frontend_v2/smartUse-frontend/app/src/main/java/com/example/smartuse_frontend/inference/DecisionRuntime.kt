package com.example.smartuse_frontend.inference

import android.content.Context
import android.os.SystemClock
import com.example.smartuse_frontend.data.RealPermissionChecker
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import com.example.smartuse_frontend.data.db.RawCaptureDbHelper
import com.example.smartuse_frontend.data.db.runFullPipeline
import com.example.smartuse_frontend.intervention.InterventionRuntime
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.metrics.AndroidMetricReadings
import com.example.smartuse_frontend.metrics.MetricRecorder
import com.example.smartuse_frontend.metrics.MetricsDbHelper
import com.example.smartuse_frontend.metrics.SqlMetricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Assembles the process's one decision-point runner (one, so its points never overlap), for the service and the debug button. */
internal object DecisionRuntime {

    @Volatile private var instance: DecisionPointRunner? = null

    fun runner(context: Context): DecisionPointRunner = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): DecisionPointRunner {
        val canonical = CanonicalDbHelper(app)
        val source = SqliteUsageSource(canonical, RawCaptureDbHelper(app))
        val profiles = SqlProfileStore(canonical)
        return DecisionPointRunner(
            isStudyLive = { JobRuntime.isStudyLive(app) },
            hasUsageAccess = { RealPermissionChecker(app).hasUsageAccess() },
            capture = { runFullPipeline(app) },
            timeline = { t -> timelineUpTo(source, ScreenObservations.log.snapshot(), t) },
            profiles = {
                val f = profiles.read(Pattern.F)
                val s = profiles.read(Pattern.S)
                if (f != null && s != null) f to s else null
            },
            models = { Models.get(app) },
            offsetS = { t -> ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(t)).totalSeconds.toDouble() },
            zone = ZoneId::systemDefault,
            firing = RealJobStateStore(app),
            // The coordinator is main-thread only.
            show = { pattern -> withContext(Dispatchers.Main) { InterventionRuntime.coordinator(app).onIntervention(pattern) } },
            newRecorder = { MetricRecorder(AndroidMetricReadings(app), SystemClock::elapsedRealtime) },
            storeMetric = { SqlMetricsStore(MetricsDbHelper(app)).insert(it) },
            logger = JobRuntime::log,
        )
    }
}

/** Debug card: "congelado", or the profile days found so far. */
internal fun debugProfileLabel(context: Context): String {
    val canonical = CanonicalDbHelper(context)
    val freezer = ProfileFreezer(
        store = SqlProfileStore(canonical),
        epochs = { capturedEpochs(SqliteUsageSource(canonical, RawCaptureDbHelper(context))) },
        offsetS = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds.toDouble() },
        forcedAt = null,
        logger = {},
    )
    return if (freezer.isFrozen()) "congelado" else "${freezer.daysSoFar()} de ${ModelRules.PROFILE_DAYS} días"
}
