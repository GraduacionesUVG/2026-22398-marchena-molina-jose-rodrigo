package com.example.smartuse_frontend.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class StudyWindowTest {

    private val signup = Instant.parse("2026-11-20T15:00:00Z")
    private val day21 = Instant.parse("2026-12-11T15:00:00Z")
    private val cutoff = LocalDate.of(2026, 12, 1)
    private val cutoffMoment = Instant.parse("2026-12-01T06:00:00Z") // 00:00 in Guatemala (UTC-6)

    @Test
    fun `the cutoff is midnight in Guatemala, not in UTC or the phone's zone`() {
        assertEquals(cutoffMoment, StudyWindow.cutoffMoment(cutoff))
    }

    @Test
    fun `without a cutoff the end is day 21`() {
        assertEquals(day21, StudyWindow.endMoment(signup, null))
    }

    @Test
    fun `a cutoff before day 21 ends collection first`() {
        assertEquals(cutoffMoment, StudyWindow.endMoment(signup, cutoff))
    }

    @Test
    fun `day 21 ends collection first when the cutoff is later`() {
        assertEquals(day21, StudyWindow.endMoment(signup, LocalDate.of(2027, 1, 15)))
    }

    @Test
    fun `the window is open until the end moment and closed from it on`() {
        assertTrue(StudyWindow.isOpen(signup, cutoff, cutoffMoment.minusMillis(1)))
        assertFalse(StudyWindow.isOpen(signup, cutoff, cutoffMoment))
        assertTrue(StudyWindow.isOpen(signup, null, day21.minusMillis(1)))
        assertFalse(StudyWindow.isOpen(signup, null, day21))
    }

    @Test
    fun `just after UTC midnight on the cutoff date it is still the day before in Guatemala`() {
        assertTrue(StudyWindow.isOpen(signup, cutoff, Instant.parse("2026-12-01T03:00:00Z")))
        assertFalse(StudyWindow.isOpen(signup, cutoff, Instant.parse("2026-12-01T06:00:00Z")))
    }

    @Test
    fun `a future cutoff before day 21 is announced`() {
        assertEquals(cutoff, StudyWindow.announcedCutoff(signup, cutoff, signup.plus(2, ChronoUnit.DAYS)))
    }

    @Test
    fun `nothing is announced without a cutoff`() {
        assertNull(StudyWindow.announcedCutoff(signup, null, signup))
    }

    @Test
    fun `a cutoff that has passed is not announced`() {
        assertNull(StudyWindow.announcedCutoff(signup, cutoff, cutoffMoment))
    }

    @Test
    fun `a cutoff at or after this participant's day 21 is not announced`() {
        assertNull(StudyWindow.announcedCutoff(signup, LocalDate.of(2027, 1, 15), signup))
        // Exactly day 21: signup at 06:00 UTC means day 21 is 00:00 Guatemala on the 21st day.
        val morningSignup = Instant.parse("2026-11-20T06:00:00Z")
        assertNull(StudyWindow.announcedCutoff(morningSignup, LocalDate.of(2026, 12, 11), morningSignup))
    }
}
