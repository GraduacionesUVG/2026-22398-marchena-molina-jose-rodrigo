package com.example.smartuse_frontend.e2e.live

import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.e2e.DeviceShell
import com.example.smartuse_frontend.e2e.DeviceStateRule
import com.example.smartuse_frontend.e2e.Live
import com.example.smartuse_frontend.e2e.walkSetupWizard
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.jobs.RealJobStateStore
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApiImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The setup wizard registers against the live backend, then the live cutoff is fetched. */
@Live
@RunWith(AndroidJUnit4::class)
class LiveRegistrationTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aParticipantRegistersAndFetchesTheCutoff() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        DeviceShell.exemptFromBatteryOptimization()
        DeviceShell.grantOverlay()
        ActivityScenario.launch(MainActivity::class.java).use { compose.walkSetupWizard(score = 37) }

        val credentials = RealCredentialStore(context)
        val userId = runBlocking { credentials.userId() }
        Log.i("LiveTest", "user_id=$userId")
        assertFalse("POST /users stored a user_id", userId.isNullOrBlank())
        assertFalse("POST /users stored a token", runBlocking { credentials.authToken() }.isNullOrBlank())

        // The app's own client against the live endpoint; refresh() itself is true only on a change.
        val fetched = runBlocking { SmartUseApiImpl().getCutoff() }
        assertTrue("GET /cutoff-deployment-group answered: $fetched", fetched is NetworkResult.Success)
        runBlocking { JobRuntime.cutoffRefresher(context).refresh() }
        // Whatever the backend holds (null today): only that it doesn't end a study that just started.
        val cutoff = runBlocking { RealJobStateStore(context).cutoffDate() }
        assertTrue("cutoff null or in the future: $cutoff", cutoff == null || cutoff.isAfter(LocalDate.now()))
        assertTrue("the study is live", runBlocking { JobRuntime.isStudyLive(context) })
    }
}
