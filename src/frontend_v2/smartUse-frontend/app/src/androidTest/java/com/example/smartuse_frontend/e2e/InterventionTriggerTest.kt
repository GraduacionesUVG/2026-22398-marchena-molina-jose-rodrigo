package com.example.smartuse_frontend.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.intervention.InterventionRuntime
import com.example.smartuse_frontend.intervention.OverlayCue
import com.example.smartuse_frontend.service.RealStudyServiceController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The trigger end to end: the real service's screen events drive the real overlay. */
@RunWith(AndroidJUnit4::class)
class InterventionTriggerTest {

    @get:Rule(order = 0) val device = DeviceStateRule()
    @get:Rule(order = 1) val backend = FakeBackend()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun intervene(): Boolean {
        var shown = false
        instrumentation.runOnMainSync { shown = InterventionRuntime.coordinator(context).onIntervention(OverlayCue.F) }
        return shown
    }

    private fun awaitUp() = await("the overlay on screen") { true.takeIf { OverlayOnScreen.isUp() } }
    private fun awaitGone() = await("the overlay gone") { true.takeIf { OverlayOnScreen.isGone() } }

    @Before fun setUp() {
        Seed.collecting(context, backend) // grants the overlay; the cycle starts the service
        await("the study service in the foreground") { true.takeIf { StudyNotification.isForegroundService(context) } }
        DeviceShell.wakeAndUnlock()
    }

    @Test
    fun anInterventionDuringUseShowsAndScreenOffRemovesIt() {
        assertTrue(intervene())
        awaitUp()

        DeviceShell.screenOff()
        awaitGone()
    }

    @Test
    fun anInterventionWhileTheScreenIsOffIsDroppedNotHeld() {
        DeviceShell.screenOff()
        Thread.sleep(1_000)
        assertFalse(intervene())

        DeviceShell.wakeAndUnlock()
        Thread.sleep(3_000)
        assertTrue("nothing is held for the next unlock", OverlayOnScreen.isGone())
    }

    @Test
    fun aSecondInterventionWhileOneIsUpIsANoOp() {
        assertTrue(intervene())
        awaitUp()
        assertFalse(intervene())
        DeviceShell.screenOff()
        awaitGone()
    }

    @Test
    fun anInterventionReplacesAPreviewOnTheRealWindows() {
        var previewed = false
        instrumentation.runOnMainSync { previewed = InterventionRuntime.coordinator(context).onPreview() }
        assertTrue(previewed)
        awaitUp()

        assertTrue("the intervention takes the screen from the preview", intervene())
        awaitUp()
        assertFalse(intervene())
        DeviceShell.screenOff()
        awaitGone()
    }

    @Test
    fun stoppingTheServiceRemovesTheOverlay() {
        assertTrue(intervene())
        awaitUp()

        RealStudyServiceController(context).stop()
        awaitGone()
    }
}
