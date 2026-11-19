package com.example.smartuse_frontend.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchDestinationTest {

    @Test
    fun `the status destination maps to the status route`() {
        assertEquals(Routes.STATUS, LaunchDestination.route(LaunchDestination.STATUS))
    }

    @Test
    fun `no destination and unknown destinations map to no route`() {
        assertNull(LaunchDestination.route(null))
        assertNull(LaunchDestination.route("somewhere-else"))
    }

    @Test
    fun `end_forms goes to the root, whose state picks the screen`() {
        assertEquals("end_forms", LaunchDestination.END_FORMS)
        assertNull(LaunchDestination.route(LaunchDestination.END_FORMS))
    }
}
