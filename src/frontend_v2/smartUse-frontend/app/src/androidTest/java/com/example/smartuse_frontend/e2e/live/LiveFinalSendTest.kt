package com.example.smartuse_frontend.e2e.live

import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.AppContainer
import com.example.smartuse_frontend.data.RegisterOutcome
import com.example.smartuse_frontend.data.SetupDraft
import com.example.smartuse_frontend.e2e.DbInspector
import com.example.smartuse_frontend.e2e.DeviceShell
import com.example.smartuse_frontend.e2e.DeviceStateRule
import com.example.smartuse_frontend.e2e.Live
import com.example.smartuse_frontend.e2e.Seed
import com.example.smartuse_frontend.e2e.await
import com.example.smartuse_frontend.e2e.submitDraftedEndForms
import com.example.smartuse_frontend.e2e.waitForText
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.study.THANKS_TITLE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole ending, with the app's own serializer, against the live POST /final. */
@Live
@RunWith(AndroidJUnit4::class)
class LiveFinalSendTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theWholeEndingReachesTheLiveBackend() {
        val outcome = runBlocking {
            AppContainer(context).registrationRepository.register(
                SetupDraft(age = 21, gender = "Femenino", universityMajor = Seed.QA_MAJOR, sasSvScore = 37),
            )
        }
        val registered = outcome as? RegisterOutcome.Registered ?: error("POST /users did not register: $outcome")
        Log.i("LiveTest", "user_id=${registered.userId}")
        // Signup moved back past day 21, the live credentials kept.
        Seed.registeredSession(context, registered.authToken, signupHoursAgo = 21L * 24 + 1, userId = registered.userId)
        Seed.metrics(context, 5)
        Seed.filledEndForms(context)
        Seed.canonicalAndRawDatabases(context)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            compose.waitForText(THANKS_TITLE, timeoutMs = 120_000)
        }

        assertNotNull("POST /final confirmed", runBlocking { RealJobStateStore(context).readEndMarks().finalSentAt })
        await("usage data erased") { true.takeIf { !DbInspector.databasesExist(context) } }
        assertTrue("metrics.db kept", DbInspector.metricsDatabaseExists(context))
    }
}
