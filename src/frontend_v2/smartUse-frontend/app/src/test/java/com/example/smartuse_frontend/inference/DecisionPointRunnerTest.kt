package com.example.smartuse_frontend.inference

import com.example.smartuse_frontend.intervention.OverlayCue
import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.metrics.DecisionKind
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.metrics.MetricReadings
import com.example.smartuse_frontend.metrics.MetricRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class DecisionPointRunnerTest {

    private val person = "u40466b"
    private val epochs = Fixtures.epochs(person)
    private val profiles = Pattern.entries.map {
        Json.decodeFromString(Profile.serializer(), Fixtures.profileJson(person, it).toString())
    }.let { it[0] to it[1] }
    private val loaded = LoadedModels(
        f = SmartUseModel.load(FileReader(File("src/main/assets/F.model"))),
        s = SmartUseModel.load(FileReader(File("src/main/assets/S.model"))),
    )

    /** A fixture point where F fires (and so something is chosen with a fresh state). */
    private val firingT: Instant = Fixtures.csv(person, "vectors_F.csv")
        .first { Fixtures.bool(it.getValue("expected_fires")) }
        .let { Instant.ofEpochMilli(Math.round(Fixtures.utcSeconds(it.getValue("t_utc")) * 1000)) }

    private val readings = object : MetricReadings {
        override fun batteryPct(): Int? = 80
        override fun currentNow(): Int? = -300
        override fun rssKb(): Long? = 1_000
    }

    private val store = FakeJobStateStore()
    private val stored = mutableListOf<DecisionMetric>()
    private val logs = mutableListOf<String>()
    private val shown = mutableListOf<OverlayCue>()
    private var showResult = true

    private fun runner(
        live: Boolean = true,
        usageAccess: Boolean = true,
        hasProfiles: Boolean = true,
        capture: suspend () -> Unit = {},
        models: () -> LoadedModels = { loaded },
        readProfiles: () -> Pair<Profile, Profile>? = { if (hasProfiles) profiles else null },
        firing: JobStateStore = store,
    ) = DecisionPointRunner(
        isStudyLive = { live },
        hasUsageAccess = { usageAccess },
        capture = capture,
        timeline = { epochs },
        profiles = readProfiles,
        models = models,
        offsetS = { Fixtures.OFFSET_S },
        zone = { ZoneOffset.ofHours(2) },
        firing = firing,
        show = { cue -> shown += cue; showResult },
        newRecorder = { MetricRecorder(readings, elapsedMs = { 0L }) },
        storeMetric = { stored += it },
        logger = { logs += it },
        clock = Clock.fixed(firingT, ZoneId.of("UTC")),
    )

    @Test
    fun `not live is skipped and not recorded`() = runBlocking {
        runner(live = false).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(emptyList<DecisionMetric>(), stored)
        assertEquals(emptyList<OverlayCue>(), shown)
        assertTrue(logs.any { it.startsWith("decision: skipped") })
    }

    @Test
    fun `no usage access is skipped and not recorded`() = runBlocking {
        runner(usageAccess = false).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(emptyList<DecisionMetric>(), stored)
    }

    @Test
    fun `no profile is skipped and not recorded`() = runBlocking {
        runner(hasProfiles = false).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(emptyList<DecisionMetric>(), stored)
        assertEquals(emptyList<OverlayCue>(), shown)
    }

    @Test
    fun `a decision point records one metric with its kind`() = runBlocking {
        runner().run(DecisionKind.ACTIVE_USE, firingT)
        assertEquals(listOf(DecisionKind.ACTIVE_USE), stored.map { it.decisionPoint })
        assertTrue(logs.any { it.startsWith("decision: kind=active_use") })
    }

    @Test
    fun `shown updates the firing state`() = runBlocking {
        runner().run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(1, shown.size)
        val state = store.firingState()
        assertEquals(1, state.shownToday)
        assertEquals(listOf(firingT), listOfNotNull(state.lastShownF, state.lastShownS))
    }

    @Test
    fun `the overlay is told both only when both scores are above their cutoffs`() = runBlocking {
        runner().run(DecisionKind.SCREEN_ON, firingT)
        val line = logs.single { it.startsWith("decision: kind=") }
        val f = Regex(" f=([^ ]+)").find(line)!!.groupValues[1].toDouble()
        val s = Regex(" s=([^ ]+)").find(line)!!.groupValues[1].toDouble()
        val both = f > loaded.f.cutoff() && s > loaded.s.cutoff()
        assertEquals(both, shown.single() == OverlayCue.BOTH)
    }

    @Test
    fun `not shown means not counted`() = runBlocking {
        showResult = false
        runner().run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(1, shown.size)
        assertEquals(FiringState(), store.firingState())
        assertEquals(1, stored.size)
    }

    @Test
    fun `the rules block a second intervention inside the cooldown`() = runBlocking {
        runner().run(DecisionKind.SCREEN_ON, firingT)
        runner().run(DecisionKind.ACTIVE_USE, firingT.plusSeconds(60))
        assertEquals(1, shown.size)
        assertEquals(2, stored.size)
    }

    @Test
    fun `model load failure is logged and not recorded`() = runBlocking {
        runner(models = { error("no asset") }).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(emptyList<DecisionMetric>(), stored)
        assertTrue(logs.any { it.startsWith("decision: failed") })
    }

    @Test
    fun `capture failure still runs on existing data`() = runBlocking {
        runner(capture = { error("usm") }).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(1, stored.size)
        assertTrue(logs.any { it.startsWith("decision: capture failed") })
    }

    @Test
    fun `two runs at once are serialised`() = runBlocking {
        var inside = 0
        var maxInside = 0
        val gate = CompletableDeferred<Unit>()
        val r = runner(capture = {
            inside++
            maxInside = maxOf(maxInside, inside)
            gate.await()
            inside--
        })
        val a = async { r.run(DecisionKind.SCREEN_ON, firingT) }
        val b = async { r.run(DecisionKind.ACTIVE_USE, firingT) }
        repeat(5) { yield() }
        gate.complete(Unit)
        a.await()
        b.await()
        assertEquals(1, maxInside)
        assertEquals(2, stored.size)
    }

    @Test
    fun `runNow ignores the rules and records nothing`() = runBlocking {
        store.setFiringState(FiringState(lastShownF = firingT, lastShownS = firingT))
        runner().runNow()
        assertEquals(1, shown.size)
        assertEquals(emptyList<DecisionMetric>(), stored)
        assertEquals(FiringState(lastShownF = firingT, lastShownS = firingT), store.firingState())
    }

    @Test
    fun `a failing profile read is logged, never thrown`() = runBlocking {
        runner(readProfiles = { error("corrupt db") }).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(emptyList<DecisionMetric>(), stored)
        assertTrue(logs.any { it.startsWith("decision: failed") })
    }

    @Test
    fun `a failing firing-state write is logged, never thrown, and the point is recorded`() = runBlocking {
        val failing = object : JobStateStore by store {
            override suspend fun setFiringState(state: FiringState) = throw java.io.IOException("disk full")
        }
        runner(firing = failing).run(DecisionKind.SCREEN_ON, firingT)
        assertEquals(1, shown.size)
        assertEquals(1, stored.size)
        assertTrue(logs.any { it.startsWith("decision: firing state not saved") })
    }
}
