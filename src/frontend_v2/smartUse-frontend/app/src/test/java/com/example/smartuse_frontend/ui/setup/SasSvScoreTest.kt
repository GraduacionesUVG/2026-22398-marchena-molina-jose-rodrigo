package com.example.smartuse_frontend.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SasSvScoreTest {

    @Test
    fun `the range ends are valid`() {
        assertEquals(10, parseSasSvScore("10"))
        assertEquals(60, parseSasSvScore("60"))
    }

    @Test
    fun `just outside the range is invalid`() {
        assertNull(parseSasSvScore("9"))
        assertNull(parseSasSvScore("61"))
    }

    @Test
    fun `empty, letters, signs and inner spaces are invalid, outer spaces are trimmed`() {
        assertNull(parseSasSvScore(""))
        assertNull(parseSasSvScore("abc"))
        assertNull(parseSasSvScore("3 7"))
        assertNull(parseSasSvScore("-20"))
        assertEquals(37, parseSasSvScore(" 37 "))
    }
}