package com.example.smartuse_frontend.e2e

import android.app.NotificationManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.jobs.JobSpecs
import com.example.smartuse_frontend.ui.terms.TERMS_ACCEPTANCE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupWizardTest {

    @get:Rule(order = 0) val backend = FakeBackend()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tap(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()

    private fun waitFor(text: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aParticipantCanFinishSetupAndTheJobsGetScheduled() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantUsageAccess()
        DeviceShell.grantNotifications()
        DeviceShell.exemptFromBatteryOptimization()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("Acepto y continúo")
            compose.onNode(hasText(TERMS_ACCEPTANCE)).performScrollTo()
            tap("Acepto y continúo")

            waitFor("Datos del participante")
            compose.onNode(hasSetTextAction() and hasText("Edad")).performTextInput("21")
            compose.onNode(hasSetTextAction() and hasText("Género")).performTextInput("Femenino")
            compose.onNode(hasSetTextAction() and hasText("Carrera universitaria")).performTextInput(Seed.QA_MAJOR)
            tap("Continuar")

            waitFor("Permiso de acceso al uso"); tap("Continuar")
            waitFor("Permiso de notificaciones"); tap("Continuar")
            waitFor("Ejecución en segundo plano"); tap("Continuar")
            // Listed brands (e.g. a Samsung on Test Lab) show the autostart step.
            runCatching { waitFor("Inicio automático", 3_000) }.onSuccess { tap("Continuar") }

            waitFor("Estado", 30_000) // the home screen's first tile
        }

        val register = backend.calls.single { it.key == "POST /users" }
        val body = JSONObject(register.body)
        assertEquals(21, body.getInt("age_at_signup"))
        assertEquals("Femenino", body.getString("gender"))
        assertEquals(Seed.QA_MAJOR, body.getString("university_major"))
        assertEquals("control", body.getString("experiment_group"))

        await("jobs scheduled after setup") {
            WorkJobs.infos(context, JobSpecs.CAPTURE_PERIODIC).takeIf { it.isNotEmpty() }
                ?.let { WorkJobs.infos(context, JobSpecs.SYNC_PERIODIC).takeIf { s -> s.isNotEmpty() } }
        }
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("the recording notification is posted", notifications.any { it.id == 3001 })
        WorkJobs.awaitQuiet(context)
        BackendRecorder.assertContract(backend)
        DbInspector.assertConsistent(context, backend)
    }
}
