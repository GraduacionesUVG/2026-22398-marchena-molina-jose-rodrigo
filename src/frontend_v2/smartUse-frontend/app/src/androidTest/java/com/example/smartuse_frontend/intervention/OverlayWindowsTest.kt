package com.example.smartuse_frontend.intervention

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smartuse_frontend.e2e.DeviceShell
import com.example.smartuse_frontend.e2e.OverlayOnScreen
import com.example.smartuse_frontend.e2e.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class OverlayWindowsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var gone = 0

    private fun windows(duration: Duration = OVERLAY_DURATION) = OverlayWindows(context, duration)
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Before fun setUp() {
        DeviceShell.wakeAndUnlock()
        DeviceShell.grantOverlay()
    }

    @After fun tearDown() {
        DeviceShell.grantOverlay()
    }

    @Test
    fun showPutsUpBothWindowsAndDismissRemovesBothOnce() {
        val overlay = windows()
        assertTrue(onMain { overlay.show(OverlayCue.F) { gone++ } })
        await("both windows up") { true.takeIf { OverlayOnScreen.isUp() } }

        onMain { overlay.dismiss() }
        onMain { overlay.dismiss() } // a second dismiss is harmless

        await("both windows gone") { true.takeIf { OverlayOnScreen.isGone() } }
        assertEquals(1, gone)
    }

    @Test
    fun closeRemovesBothWindowsAndALaterDismissDoesNotReportAgain() {
        val overlay = windows()
        onMain { overlay.show(OverlayCue.F) { gone++ } }
        await("both windows up") { true.takeIf { OverlayOnScreen.isUp() } }

        onMain { overlay.clickClose() }
        onMain { overlay.dismiss() }

        await("both windows gone") { true.takeIf { OverlayOnScreen.isGone() } }
        assertEquals(1, gone)
    }

    @Test
    fun theOverlayRemovesItselfWhenTheCountdownEnds() {
        val overlay = windows(Duration.ofSeconds(2))
        onMain { overlay.show(OverlayCue.F) { gone++ } }
        await("both windows up") { true.takeIf { OverlayOnScreen.isUp() } }

        await("auto-dismissed", timeoutMs = 10_000) { true.takeIf { OverlayOnScreen.isGone() } }
        assertEquals(1, onMain { gone })
    }

    @Test
    fun withoutThePermissionShowFailsAndLeavesNothingBehind() {
        DeviceShell.revokeOverlay()
        val overlay = windows()

        assertFalse(onMain { overlay.show(OverlayCue.F) { gone++ } })

        Thread.sleep(1_000)
        assertTrue(OverlayOnScreen.isGone())
        assertEquals(0, gone)
    }

    @Test
    fun aSecondShowWhileUpIsRefused() {
        val overlay = windows()
        onMain { overlay.show(OverlayCue.F) { gone++ } }

        assertFalse(onMain { overlay.show(OverlayCue.F) { gone++ } })

        onMain { overlay.dismiss() }
        await("both windows gone") { true.takeIf { OverlayOnScreen.isGone() } }
        assertEquals(1, gone)
    }
}
