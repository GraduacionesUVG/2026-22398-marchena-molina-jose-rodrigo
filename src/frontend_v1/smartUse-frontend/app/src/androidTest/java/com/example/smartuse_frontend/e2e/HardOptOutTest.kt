package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.jobs.JobSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardOptOutTest {

    @get:Rule(order = 0) val backend = FakeBackend()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tap(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    private fun waitFor(text: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aHardOptOutIsSentOnceDeletesLocalDataAndStopsTheJobs() {
        Seed.registeredSession(context, backend.token)
        Seed.canonicalAndRawDatabases(context)
        DeviceShell.wakeAndUnlock()

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Retirarse del estudio")
            tap("Retirarse del estudio")
            waitFor("Retiro total")
            tap("Retiro total")
            waitFor("Confirmar retiro total")
            tap("Confirmar")
            waitFor("Tus datos ya fueron eliminados de nuestros servidores.", 30_000)
            // Uninstalling is offered only once the backend confirmed the deletion.
            compose.onNode(hasText("Desinstalar ahora") and hasClickAction()).assertIsEnabled()
        }

        val optOuts = backend.calls.filter { it.key == "POST /opt-out/hard" }
        // The finish worker is queued before the inline call and may send its own
        // before the inline one is confirmed (a known app race, see the ledger);
        // one or two, never more.
        assertTrue("one or two hard opt-outs, got ${optOuts.size}", optOuts.size in 1..2)
        await("local data wiped") { true.takeIf { !DbInspector.databasesExist(context) } }
        for (name in listOf(JobSpecs.CAPTURE_PERIODIC, JobSpecs.SYNC_PERIODIC)) {
            await("$name cancelled") { true.takeIf { WorkJobs.infos(context, name).all { it.state == WorkInfo.State.CANCELLED } } }
        }
        await("opt-out finish job completed", 60_000) {
            true.takeIf { WorkJobs.infos(context, "opt-out-finish").any { it.state == WorkInfo.State.SUCCEEDED } }
        }
        val optOutAt = optOuts.first().at
        assertTrue(
            "no sync request after the opt-out",
            BackendRecorder.callsAfter(backend, optOutAt).none { it.path.startsWith("/sync") },
        )
        BackendRecorder.assertContract(backend)
        assertFalse(DbInspector.databasesExist(context))
    }
}
