package com.example.smartuse_frontend.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.jobs.JobName
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@Soak
@RunWith(AndroidJUnit4::class)
class NaturalRunSoakTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun captureRunsByItselfOnTheShortDebugInterval() {
        val limits = JobLimits.current
        assumeTrue(
            "needs smartuse.cycleIntervalMinutes=15 in local.properties (debug build)",
            limits.effectiveCycleIntervalMinutes <= 15,
        )
        Seed.collecting(context, backend) // includes the immediate first runs
        // The next runs are WorkManager's own: at least ~one interval later.
        val secondPeriod = Instant.now().plus(Duration.ofMinutes(10))
        val budget = Duration.ofMinutes(35).toMillis()
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, secondPeriod, timeoutMs = budget)
        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context)
    }
}
