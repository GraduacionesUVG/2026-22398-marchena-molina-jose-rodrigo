package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.intervention.OverlayCue
import com.example.smartuse_frontend.intervention.overlayCue
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.metrics.DecisionKind
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.metrics.MetricRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Readings while a decision point runs. */
private const val SAMPLE_EVERY_MS = 100L

/**
 * One decision point: fresh capture → the epochs up to t →
 * sessions → the F and S vectors → both scores → the execution rules → the
 * overlay, then one metric record. The scores are logged only, never stored.
 * Points that can't run the models (not live, no usage access, profiles not
 * frozen yet) and points that fail record nothing. One at a time.
 */
class DecisionPointRunner(
    private val isStudyLive: suspend () -> Boolean,
    private val hasUsageAccess: () -> Boolean,
    private val capture: suspend () -> Unit,
    /** The epochs up to t (epoch ms). */
    private val timeline: (Long) -> List<ModelEpoch>,
    private val profiles: () -> Pair<Profile, Profile>?,
    private val models: () -> LoadedModels,
    /** The local offset at t (epoch ms), in seconds. */
    private val offsetS: (Long) -> Double,
    private val zone: () -> ZoneId,
    private val firing: JobStateStore,
    /** Shows the overlay on the main thread; true only if it is up. */
    private val show: suspend (OverlayCue) -> Boolean,
    private val newRecorder: () -> MetricRecorder,
    private val storeMetric: (DecisionMetric) -> Unit,
    private val logger: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val sampleEveryMs: Long = SAMPLE_EVERY_MS,
) {
    private val oneAtATime = Mutex()

    suspend fun run(kind: DecisionKind, t: Instant) = oneAtATime.withLock {
        // It runs in the service's scope: nothing may escape and take the process down.
        val skip = try {
            when {
                !isStudyLive() -> "study not live"
                !hasUsageAccess() -> "no usage access"
                profiles() == null -> "profiles not frozen"
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("decision: failed: ${e::class.java.simpleName}: ${e.message}")
            return@withLock
        }
        if (skip != null) {
            logger("decision: skipped ($skip)")
            return@withLock
        }
        val recorder = newRecorder()
        recorder.start()
        val ran = coroutineScope {
            // Its own thread: the work blocks on SQLite and the models.
            val sampler = launch(Dispatchers.Default) { sampleEvery(recorder) }
            try {
                decide(kind.wire, t, applyRules = true)
            } finally {
                sampler.cancel()
            }
        }
        if (ran) record(recorder, kind)
    }

    /** Debug: a decision point now, profiles required, rules ignored and untouched. Never recorded. */
    suspend fun runNow() = oneAtATime.withLock {
        if (profiles() == null) {
            logger("decision: skipped (profiles not frozen)")
            return@withLock
        }
        decide("debug", clock.instant(), applyRules = false)
    }

    /** False when it failed before deciding: such a point is not recorded. */
    private suspend fun decide(label: String, t: Instant, applyRules: Boolean): Boolean {
        try {
            capture()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("decision: capture failed, using what is there: ${e::class.java.simpleName}: ${e.message}")
        }
        val (pattern, scores) = try {
            val (profileF, profileS) = profiles() ?: return false
            val tMs = t.toEpochMilli()
            val offset = offsetS(tMs)
            val sessions = buildSessions(timeline(tMs))
            val loaded = models()
            val tS = tMs / 1000.0
            val scores = Scores(
                f = loaded.f.score(extract(Pattern.F, sessions, tS, profileF, offset)),
                s = loaded.s.score(extract(Pattern.S, sessions, tS, profileS, offset)),
                fCutoff = loaded.f.cutoff(),
                sCutoff = loaded.s.cutoff(),
            )
            val state = if (applyRules) firing.firingState() else FiringState()
            val chosen = choosePattern(scores, t, zone(), state)
            logger("decision: kind=$label t=$t f=${scores.f} s=${scores.s} chosen=${chosen?.name ?: "none"}")
            chosen to scores
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("decision: failed: ${e::class.java.simpleName}: ${e.message}")
            return false
        }
        if (pattern != null && show(overlayCue(pattern, scores)) && applyRules) {
            try {
                firing.setFiringState(firing.firingState().afterShown(pattern, t, zone()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the overlay is up either way.
                logger("decision: firing state not saved: ${e::class.java.simpleName}: ${e.message}")
            }
        }
        return true
    }

    private suspend fun sampleEvery(recorder: MetricRecorder) {
        while (true) {
            delay(sampleEveryMs)
            try {
                recorder.sample()
            } catch (e: Exception) {
                logger("metrics: sampler failed: ${e::class.java.simpleName}: ${e.message}")
                return
            }
        }
    }

    private fun record(recorder: MetricRecorder, kind: DecisionKind) {
        try {
            val metric = recorder.finish(kind)
            storeMetric(metric)
            logger("metrics: ${metric.describe()}")
        } catch (e: Exception) {
            logger("metrics: record lost: ${e::class.java.simpleName}: ${e.message}")
        }
    }
}
