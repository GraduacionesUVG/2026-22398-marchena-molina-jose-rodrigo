package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.MainActivity
import com.example.smartuse_frontend.ui.optout.CONFIRMED_LINE
import com.example.smartuse_frontend.ui.optout.PENDING_LINE
import com.example.smartuse_frontend.ui.optout.RETRYING_LINE
import okhttp3.mockwebserver.MockResponse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Not a test: the opt-out pages of the data-collection app, saved as PNGs to
 * [DIR] for the thesis figures. Skipped unless the instrumentation argument
 * `screenshots=true` is set (`python testlab/run_screenshots.py --test-class ScreenshotOptOuts`).
 * Each method is a fresh install (orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotOptOuts {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        assumeTrue("screenshot tour: run with screenshots=true", InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        DeviceShell.run("mkdir -p $DIR")
        DeviceShell.run("cmd alarm set-timezone America/Guatemala")
        Seed.registeredSession(context, backend.token)
        Seed.canonicalAndRawDatabases(context)
        DeviceShell.wakeAndUnlock()
    }

    private fun shot(name: String, settleMs: Long = 700) {
        runCatching { compose.waitForIdle() }
        Thread.sleep(settleMs)
        DeviceShell.run("screencap -p $DIR/$name.png")
    }

    private fun tapButton(text: String) =
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick() // the page fits on screen: no scrolling

    /** Home → Retirarse del estudio, the page with both options. */
    private fun openOptOutPage() {
        compose.waitForText("Retirarse del estudio", 30_000)
        compose.tapText("Retirarse del estudio")
        compose.waitForText("Retiro total")
    }

    /** The options page, the partial confirmation, then the end screen after a partial opt-out. */
    @Test
    fun a_softOptOut() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openOptOutPage()
            shot("80_optout_page")
            tapButton("Retiro parcial")
            compose.waitForText("Confirmar retiro parcial")
            shot("81_optout_soft_confirm")
            compose.tapText("Confirmar")
            compose.waitForText("Retiro completado. Gracias por tu participación. Ya puedes desinstalar la aplicación.", 30_000)
            shot("82_optout_soft_done", settleMs = 1_200)
        }
    }

    /** The total confirmation, then the end screen once the backend confirmed the deletion. */
    @Test
    fun b_hardOptOutConfirmed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openOptOutPage()
            tapButton("Retiro total")
            compose.waitForText("Confirmar retiro total")
            shot("83_optout_hard_confirm")
            compose.tapText("Confirmar")
            compose.waitForText(CONFIRMED_LINE, 30_000)
            shot("86_optout_hard_done", settleMs = 1_200)
        }
    }

    /**
     * A total opt-out the backend hasn't confirmed: first in flight (a slow
     * answer), then retrying (refused). Uninstall stays disabled with its hint.
     */
    @Test
    fun c_hardOptOutPendingThenRetrying() {
        backend.respond("POST /opt-out/hard", MockResponse().setResponseCode(500).setBody("{}").setHeadersDelay(8, TimeUnit.SECONDS))
        backend.respondAlways("POST /opt-out/hard", 500)
        ActivityScenario.launch(MainActivity::class.java).use {
            openOptOutPage()
            tapButton("Retiro total")
            compose.waitForText("Confirmar retiro total")
            compose.tapText("Confirmar")
            runCatching {
                compose.waitForText("Procesando tu retiro…", 5_000)
                shot("83b_optout_processing", settleMs = 300)
            }
            compose.waitForText(PENDING_LINE, 30_000)
            shot("84_optout_hard_pending", settleMs = 500)
            compose.waitForText(RETRYING_LINE, 60_000)
            shot("85_optout_hard_retrying", settleMs = 1_000)
        }
    }

    private companion object {
        const val DIR = "/sdcard/Download/smartuse_screens"
    }
}
