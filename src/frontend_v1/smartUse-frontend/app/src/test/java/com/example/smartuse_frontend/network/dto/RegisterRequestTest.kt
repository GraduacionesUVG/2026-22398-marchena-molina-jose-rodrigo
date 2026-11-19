package com.example.smartuse_frontend.network.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterRequestTest {

    @Test
    fun `control request serializes snake_case keys and omits experiment_config`() {
        val json = appJson.encodeToString(
            RegisterRequest.serializer(),
            RegisterRequest(
                ageAtSignup = 21,
                gender = "Femenino",
                universityMajor = "Psicología",
                testScore = 34,
                experimentGroup = "control",
                experimentConfig = null,
            ),
        )

        assertTrue(json.contains("\"age_at_signup\":21"))
        assertTrue(json.contains("\"university_major\":\"Psicología\""))
        assertTrue(json.contains("\"test_score\":34"))
        assertTrue(json.contains("\"experiment_group\":\"control\""))
        assertFalse(json.contains("experiment_config"))
    }

    @Test
    fun `response parses and ignores unknown keys`() {
        val body = """{"user_id":"u-9","auth_token":"tok","extra":true}"""
        val parsed = appJson.decodeFromString(RegisterResponse.serializer(), body)
        assertEquals("u-9", parsed.userId)
        assertEquals("tok", parsed.authToken)
    }
}
