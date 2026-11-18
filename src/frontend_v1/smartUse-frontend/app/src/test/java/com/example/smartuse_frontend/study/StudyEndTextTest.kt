package com.example.smartuse_frontend.study

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StudyEndTextTest {

    @Test
    fun `the announcement names the date with a Spanish month`() {
        assertEquals(
            "El periodo de recolección acabará el 1 de diciembre de 2026. Luego de esa fecha no podrás " +
                "realizar el retiro total del estudio y la aplicación dejará de recolectar información.",
            cutoffAnnouncement(LocalDate.of(2026, 12, 1)),
        )
    }
}
