package com.example.smartuse_frontend.intervention

import com.example.smartuse_frontend.inference.Pattern
import com.example.smartuse_frontend.inference.Scores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class OverlayContentTest {

    @Test
    fun `the tint stays at or under the touch-through limit, or Android blocks the touches`() {
        assertTrue(OVERLAY_TINT_ALPHA > 0f)
        assertTrue(OVERLAY_TINT_ALPHA <= MAX_TOUCH_THROUGH_ALPHA)
    }

    @Test
    fun `the overlay lasts 10 seconds`() {
        assertEquals(Duration.ofSeconds(10), OVERLAY_DURATION)
    }

    @Test
    fun `the countdown reads in seconds, singular at one`() {
        assertEquals("Esta pestaña se cerrará en 10 segundos", countdownText(10))
        assertEquals("Esta pestaña se cerrará en 1 segundo", countdownText(1))
    }

    @Test
    fun `the footer names the predicted pattern`() {
        assertEquals("Posibles revisiones frecuentes del teléfono en la próxima hora", footerText(OverlayCue.F))
        assertEquals("Este periodo de uso podría estar alargándose sin pausa", footerText(OverlayCue.S))
        assertEquals("Posibles revisiones frecuentes o uso prolongado en la siguiente hora", footerText(OverlayCue.BOTH))
    }

    @Test
    fun `the preview footer says it is a test`() {
        assertEquals(PREVIEW_FOOTER, footerText(OverlayCue.PREVIEW))
    }

    private fun scores(f: Double, s: Double) = Scores(f = f, s = s, fCutoff = 0.4, sCutoff = 0.2)

    @Test
    fun `the cue is the chosen pattern when only it fired`() {
        assertEquals(OverlayCue.F, overlayCue(Pattern.F, scores(f = 0.9, s = 0.1)))
        assertEquals(OverlayCue.S, overlayCue(Pattern.S, scores(f = 0.1, s = 0.9)))
    }

    @Test
    fun `the cue is both when both models are above their cutoffs, whichever was chosen`() {
        assertEquals(OverlayCue.BOTH, overlayCue(Pattern.F, scores(f = 0.9, s = 0.9)))
        assertEquals(OverlayCue.BOTH, overlayCue(Pattern.S, scores(f = 0.5, s = 0.9)))
    }

    @Test
    fun `a score exactly at its cutoff has not fired`() {
        assertNotEquals(OverlayCue.BOTH, overlayCue(Pattern.F, scores(f = 0.9, s = 0.2)))
    }
}
