package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.data.AppContainer
import com.example.smartuse_frontend.data.RealCredentialStore
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import com.example.smartuse_frontend.study.OPTED_OUT_TEXT
import com.example.smartuse_frontend.study.SENDING_TITLE
import com.example.smartuse_frontend.study.THANKS_TITLE
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndingsTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Day 21 is decided from the stored signup alone: the ending marks, keeps
    // everything and asks for the forms; nothing is sent until they are filled.
    @Test
    fun day21AsksForTheFormsWithoutErasingOrAnyRequest() {
        Seed.registeredSession(context, backend.token, signupHoursAgo = 21L * 24 + 1)
        Seed.canonicalAndRawDatabases(context)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()

        val launchedAt = System.currentTimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use { compose.waitForText(FormDefinitions.USABILITY.sections.first().title) }

        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.FormsPending).text)
        assertTrue("usage data kept", DbInspector.databasesExist(context))
        assertNotNull("credentials kept", runBlocking { RealCredentialStore(context).authToken() })
        assertEquals(emptyList<RecordedCall>(), BackendRecorder.callsAfter(backend, launchedAt))
    }

    @Test
    fun anOptOutSendsOptOut() {
        Seed.registeredSession(context, backend.token)
        Seed.filledEndForms(context)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()
        runBlocking { AppContainer(context).optOutRepository.optOut() }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            compose.waitForText(THANKS_TITLE, timeoutMs = 60_000)
            compose.waitForText(OPTED_OUT_TEXT)
        }

        val body = JSONObject(backend.calls.single { it.key == "POST /final" }.body)
        assertEquals("opt_out", body.getString("end_reason"))
    }

    @Test
    fun aFailedSendIsRetriedUntilStored() {
        Seed.endedStudy(context, backend.token)
        Seed.filledEndForms(context)
        backend.respond("POST /final", MockResponse().setResponseCode(500))
        DeviceShell.wakeAndUnlock()

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            // The first retry waits for the 30 s backoff.
            compose.waitForText(THANKS_TITLE, timeoutMs = 120_000)
        }

        val sends = backend.calls.filter { it.key == "POST /final" }
        assertEquals(listOf(500, 201), sends.map { it.status })
    }

    @Test
    fun sendingIsShownWhileTheBackendRefuses() {
        Seed.endedStudy(context, backend.token)
        Seed.filledEndForms(context)
        Seed.canonicalAndRawDatabases(context)
        backend.respondAlways("POST /final", 500)
        DeviceShell.wakeAndUnlock()

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.submitDraftedEndForms()
            compose.waitForText(SENDING_TITLE)
        }
        await("the first attempt refused") { true.takeIf { backend.calls.any { it.key == "POST /final" } } }
        assertTrue("usage data kept until the send is confirmed", DbInspector.databasesExist(context))
    }
}