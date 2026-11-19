package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.jobs.BackgroundJobScheduler
import com.example.smartuse_frontend.jobs.JobName
import com.example.smartuse_frontend.jobs.JobSpecs
import com.example.smartuse_frontend.study.END_OF_COLLECTION_TEXT
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CutoffEndTest {

    @get:Rule(order = 0) val backend = FakeBackend()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aPastCutoffEndsCollectionErasesEverythingAndStopsTheJobs() {
        Seed.registeredSession(context, backend.token)
        Seed.canonicalAndRawDatabases(context)
        assertTrue(DbInspector.databasesExist(context))
        DeviceShell.grantUsageAccess()

        // Jobs scheduled and settled while collection is still open, so the ending
        // below is the launch's own (LaunchCheck), not a job's gate winning a race.
        val scheduledAt = Instant.now()
        BackgroundJobScheduler(context).ensureScheduled()
        WorkJobs.awaitCompletedAfter(context, JobName.CAPTURE, scheduledAt)
        await("sync-now settled") {
            true.takeIf { WorkJobs.infos(context, JobSpecs.SYNC_NOW).none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING } }
        }
        WorkJobs.awaitQuiet(context)

        val yesterday = LocalDate.now().minusDays(1)
        Seed.storedCutoff(context, yesterday)
        backend.cutoff = yesterday.toString()
        DeviceShell.wakeAndUnlock()

        val launchedAt = System.currentTimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(END_OF_COLLECTION_TEXT)).fetchSemanticsNodes().isNotEmpty() }
        }

        await("local data erased") { true.takeIf { !DbInspector.databasesExist(context) } }
        val endedAt = System.currentTimeMillis()
        assertNull("credentials erased", runBlocking { RealCredentialStore(context).authToken() })
        for (name in listOf(JobSpecs.CAPTURE_PERIODIC, JobSpecs.SYNC_PERIODIC)) {
            await("$name cancelled") { true.takeIf { WorkJobs.infos(context, name).all { it.state == WorkInfo.State.CANCELLED } } }
        }
        await("no jobs left in JobScheduler") { true.takeIf { DeviceShell.scheduledJobs().isEmpty() } }
        // Belt and braces: should a job still race the launch, its gate fetches the
        // cutoff and runs the ending itself. Only that fetch may follow the launch,
        // and nothing at all may follow the end.
        assertEquals(
            "only a job's cutoff fetch after launch",
            emptyList<RecordedCall>(),
            BackendRecorder.callsAfter(backend, launchedAt).filter { it.key != "GET /cutoff" },
        )
        assertEquals("no request after the end", emptyList<RecordedCall>(), BackendRecorder.callsAfter(backend, endedAt))
        assertFalse(DbInspector.databasesExist(context))
    }
}
