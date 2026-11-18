package com.example.smartuse_frontend.intervention

import com.example.smartuse_frontend.service.ScreenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionCoordinatorTest {

    private class FakePresenter(var succeeds: Boolean = true) : OverlayPresenter {
        var shows = 0
        var dismissals = 0
        var lastCue: OverlayCue? = null
        private var onGone: (() -> Unit)? = null

        override fun show(cue: OverlayCue, onGone: () -> Unit): Boolean {
            shows++
            lastCue = cue
            if (succeeds) this.onGone = onGone
            return succeeds
        }

        override fun dismiss() {
            dismissals++
            gone()
        }

        /** The participant taps close, or the 10 s run out: the coordinator can't tell which. */
        fun gone() {
            onGone?.invoke()
            onGone = null
        }
    }

    private val presenter = FakePresenter()
    private var inUse = true
    private var canDraw = true
    private var mainThreadChecks = 0
    private val coordinator = InterventionCoordinator(
        isInUse = { inUse },
        canDrawOverlays = { canDraw },
        presenter = presenter,
        logger = {},
        checkMainThread = { mainThreadChecks++ },
    )

    init {
        coordinator.onServiceStarted()
    }

    @Test
    fun `shows during use and returns true`() {
        assertTrue(coordinator.onIntervention(OverlayCue.F))
        assertEquals(1, presenter.shows)
        assertTrue(coordinator.isShowing)
    }

    @Test
    fun `S shows the same overlay`() {
        assertTrue(coordinator.onIntervention(OverlayCue.S))
        assertEquals(1, presenter.shows)
    }

    @Test
    fun `overlay already up is a no-op returning false`() {
        coordinator.onIntervention(OverlayCue.F)
        assertFalse(coordinator.onIntervention(OverlayCue.S))
        assertEquals(1, presenter.shows)
    }

    @Test
    fun `after the overlay closes, a new intervention shows again`() {
        coordinator.onIntervention(OverlayCue.F)
        presenter.gone()
        assertTrue(coordinator.onIntervention(OverlayCue.F))
        assertEquals(2, presenter.shows)
    }

    @Test
    fun `not in use returns false and holds nothing`() {
        inUse = false
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        inUse = true
        coordinator.onScreenEvent(ScreenEvent.USER_PRESENT)
        assertEquals(0, presenter.shows)
    }

    @Test
    fun `no permission returns false`() {
        canDraw = false
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        assertEquals(0, presenter.shows)
        assertFalse(coordinator.isShowing)
    }

    @Test
    fun `presenter failure returns false`() {
        presenter.succeeds = false
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        assertEquals(1, presenter.shows)
        assertFalse(coordinator.isShowing)
    }

    @Test
    fun `screen off dismisses`() {
        coordinator.onIntervention(OverlayCue.F)
        coordinator.onScreenEvent(ScreenEvent.SCREEN_OFF)
        assertEquals(1, presenter.dismissals)
        assertFalse(coordinator.isShowing)
        coordinator.onScreenEvent(ScreenEvent.USER_PRESENT)
        assertEquals(1, presenter.shows)
    }

    @Test
    fun `nothing after service stopped`() {
        coordinator.onIntervention(OverlayCue.F)
        coordinator.onServiceStopped()
        assertEquals(1, presenter.dismissals)
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        coordinator.onServiceStarted()
        presenter.gone()
        assertTrue(coordinator.onIntervention(OverlayCue.F))
    }

    @Test
    fun `nothing before the service starts`() {
        val fresh = InterventionCoordinator({ true }, { true }, presenter, {})
        assertFalse(fresh.onIntervention(OverlayCue.F))
        assertEquals(0, presenter.shows)
    }

    @Test
    fun `nothing after study ended`() {
        coordinator.onIntervention(OverlayCue.F)
        coordinator.onStudyEnded()
        assertTrue(presenter.dismissals >= 1)
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        assertEquals(1, presenter.shows)
    }

    @Test
    fun `the preview shows the preview footer`() {
        assertTrue(coordinator.onPreview())
        assertEquals(OverlayCue.PREVIEW, presenter.lastCue)
        assertTrue(coordinator.isShowing)
    }

    @Test
    fun `the preview needs neither the service nor the in-use check`() {
        val fresh = InterventionCoordinator({ false }, { true }, presenter, {})
        assertTrue(fresh.onPreview())
    }

    @Test
    fun `the preview needs the overlay permission`() {
        canDraw = false
        assertFalse(coordinator.onPreview())
        assertEquals(0, presenter.shows)
    }

    @Test
    fun `no preview after the study ended`() {
        coordinator.onStudyEnded()
        assertFalse(coordinator.onPreview())
        assertEquals(0, presenter.shows)
    }

    @Test
    fun `a preview never covers an intervention`() {
        coordinator.onIntervention(OverlayCue.F)
        assertFalse(coordinator.onPreview())
        assertEquals(1, presenter.shows)
        assertEquals(0, presenter.dismissals)
    }

    @Test
    fun `a second preview while one is up is a no-op`() {
        coordinator.onPreview()
        assertFalse(coordinator.onPreview())
        assertEquals(1, presenter.shows)
    }

    @Test
    fun `an intervention replaces a preview and is shown`() {
        coordinator.onPreview()
        assertTrue(coordinator.onIntervention(OverlayCue.S))
        assertEquals(1, presenter.dismissals)
        assertEquals(2, presenter.shows)
        assertEquals(OverlayCue.S, presenter.lastCue)
        assertTrue(coordinator.isShowing)
        // The replaced preview is gone; the intervention still blocks a new one.
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        assertFalse(coordinator.onPreview())
    }

    @Test
    fun `an intervention refused for its own reason leaves the preview up`() {
        coordinator.onPreview()
        inUse = false
        assertFalse(coordinator.onIntervention(OverlayCue.F))
        assertEquals(0, presenter.dismissals)
        assertTrue(coordinator.isShowing)
    }

    @Test
    fun `after a preview closes, an intervention shows normally`() {
        coordinator.onPreview()
        presenter.gone()
        assertTrue(coordinator.onIntervention(OverlayCue.F))
        assertEquals(0, presenter.dismissals)
    }

    @Test
    fun `every entry point checks the main thread`() {
        val before = mainThreadChecks
        coordinator.onIntervention(OverlayCue.F)
        coordinator.onScreenEvent(ScreenEvent.SCREEN_OFF)
        coordinator.onServiceStopped()
        coordinator.onServiceStarted()
        coordinator.onPreview()
        assertEquals(before + 5, mainThreadChecks)
    }
}
