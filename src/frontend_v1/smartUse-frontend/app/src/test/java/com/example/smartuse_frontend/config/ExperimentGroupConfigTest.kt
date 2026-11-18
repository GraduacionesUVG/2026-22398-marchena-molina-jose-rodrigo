package com.example.smartuse_frontend.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentGroupConfigTest {

    @Test
    fun `each disguised month maps to its group`() {
        assertEquals(ExperimentGroup.CONTROL, ExperimentGroupConfig.resolve("Julio"))
        assertEquals(ExperimentGroup.RATE, ExperimentGroupConfig.resolve("Agosto"))
        assertEquals(ExperimentGroup.INFERENCE, ExperimentGroupConfig.resolve("Septiembre"))
    }

    @Test
    fun `unknown option resolves to null`() {
        assertNull(ExperimentGroupConfig.resolve("Octubre"))
        assertNull(ExperimentGroupConfig.resolve(""))
    }

    @Test
    fun `options are exactly the three months in display order`() {
        assertEquals(listOf("Julio", "Agosto", "Septiembre"), ExperimentGroupConfig.options.map { it.first })
    }

    @Test
    fun `wire values match the backend enum`() {
        assertEquals("control", ExperimentGroup.CONTROL.wireValue)
        assertEquals("rate", ExperimentGroup.RATE.wireValue)
        assertEquals("inference", ExperimentGroup.INFERENCE.wireValue)
    }
}
