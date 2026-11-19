package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.forms.QuestionKind
import com.example.smartuse_frontend.notification.StudyNotice
import com.example.smartuse_frontend.notification.studyNoticeContent
import com.example.smartuse_frontend.study.THANKS_TITLE
import com.example.smartuse_frontend.ui.endforms.END_FORMS_CONFIRM
import com.example.smartuse_frontend.ui.endforms.END_FORMS_NEXT
import com.example.smartuse_frontend.ui.endforms.END_FORMS_SEND
import com.example.smartuse_frontend.ui.endforms.choiceTag
import com.example.smartuse_frontend.ui.endforms.textTag
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole ending through the UI: day 21 → the end forms → one POST /final → erase → thanks. */
@RunWith(AndroidJUnit4::class)
class EndFormsFlowTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tapTag(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun day21LeadsToTheFormsAndOneFinalSend() {
        Seed.registeredSession(context, backend.token, signupHoursAgo = 21L * 24 + 1)
        Seed.canonicalAndRawDatabases(context)
        Seed.metrics(context, 3)
        DeviceShell.grantNotifications()
        DeviceShell.wakeAndUnlock()

        ActivityScenario.launch(MainActivity::class.java).use {
            val sections = FormDefinitions.USABILITY.sections
            compose.waitForText(FormDefinitions.USABILITY.title)
            sections.forEachIndexed { i, section ->
                compose.waitForText(section.title)
                section.questions.forEach { q ->
                    when (val kind = q.kind) {
                        is QuestionKind.SingleChoice -> tapTag(choiceTag(q.id, kind.options.last().value))
                        is QuestionKind.OpenText -> if (q.id == "d12_1") {
                            compose.onNodeWithTag(textTag(q.id)).performScrollTo().performTextInput("Todo bien")
                        }
                    }
                }
                compose.scrollToAndTapText(if (i == sections.lastIndex) END_FORMS_SEND else END_FORMS_NEXT)
            }
            compose.tapText(END_FORMS_CONFIRM)

            compose.waitForText(THANKS_TITLE, timeoutMs = 60_000)
        }

        val send = backend.calls.single { it.key == "POST /final" }
        assertEquals("Bearer ${backend.token}", send.authorization)
        val body = JSONObject(send.body)
        assertEquals("completed", body.getString("end_reason"))
        val forms = body.getJSONObject("forms")
        assertFalse("no sas_sv end form", forms.has("sas_sv"))
        val answers = forms.getJSONObject("usability").getJSONObject("answers")
        val choices = FormDefinitions.USABILITY.questions.count { it.kind is QuestionKind.SingleChoice }
        assertEquals(choices + 1, answers.length()) // every choice + the one open answer given
        assertEquals(5, answers.getInt("d1_1"))
        assertEquals(0, answers.getInt("d11_1")) // "No", the last option
        assertEquals("Todo bien", answers.getString("d12_1"))
        val metrics = body.getJSONArray("metrics")
        assertEquals(3, metrics.length())
        assertFalse(metrics.getJSONObject(0).has("id"))
        await("the usage data erased") { true.takeIf { !DbInspector.databasesExist(context) } }
        assertTrue("metrics kept", DbInspector.metricsDatabaseExists(context))
        StudyNotification.awaitText(context, studyNoticeContent(StudyNotice.Finished(optedOut = false)).text)
        BackendRecorder.assertContract(backend)
    }
}