package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.inference.DecisionRuntime
import com.example.smartuse_frontend.metrics.DecisionKind
import com.example.smartuse_frontend.metrics.MetricsDbHelper
import com.example.smartuse_frontend.metrics.SqlMetricsStore
import com.example.smartuse_frontend.study.THANKS_TITLE
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Real decision points (frozen fixture profiles, the bundled models, the real
 * runner and store) write metric rows, and the final send carries exactly those.
 */
@RunWith(AndroidJUnit4::class)
class MetricsToFinalSendTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realDecisionPointsReachTheFinalSend() {
        Seed.registeredSession(context, backend.token) // signup 3 h ago: live
        Seed.frozenProfiles(context)
        DeviceShell.grantUsageAccess()
        // No overlay permission: a score above a cutoff can't cover the forms; the point is recorded either way.
        DeviceShell.wakeAndUnlock()
        val runner = DecisionRuntime.runner(context)
        repeat(3) { runBlocking { runner.run(DecisionKind.SCREEN_ON, Instant.now()) } }

        val stored = SqlMetricsStore(MetricsDbHelper(context)).readAll()
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.decisionPoint == DecisionKind.SCREEN_ON && it.durationMs > 0 })

        // The ending comes after the points: signup moved back past day 21.
        Seed.registeredSession(context, backend.token, signupHoursAgo = 21L * 24 + 1)
        Seed.filledEndForms(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            compose.waitForText(THANKS_TITLE, timeoutMs = 60_000)
        }

        val metrics = JSONObject(backend.calls.single { it.key == "POST /final" }.body).getJSONArray("metrics")
        val sent = (0 until metrics.length()).map { metrics.getJSONObject(it) }
        assertEquals(stored.map { it.durationMs }.sorted(), sent.map { it.getLong("duration_ms") }.sorted())
        assertTrue(sent.all { it.getString("decision_point") == "screen_on" && !it.has("id") })
        assertTrue("metrics.db survives the erase", DbInspector.metricsDatabaseExists(context))
    }
}
