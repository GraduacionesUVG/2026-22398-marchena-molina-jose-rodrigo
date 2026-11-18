package com.example.smartuse_frontend.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** ModelRules mirrors the researcher's production.json. */
class ModelRulesTest {

    private val json: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/production.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun rule(name: String) = json["rules"]!!.jsonObject[name]!!.jsonPrimitive.double

    @Test
    fun `matches production json`() {
        assertEquals(rule("k_min"), ModelRules.K_MIN.toDouble(), 0.0)
        assertEquals(rule("cooldown_min"), ModelRules.COOLDOWN_MIN.toDouble(), 0.0)
        assertEquals(rule("episode_min"), ModelRules.EPISODE_MIN.toDouble(), 0.0)
        assertEquals(rule("daily_cap"), ModelRules.DAILY_CAP.toDouble(), 0.0)
        assertEquals(20, ModelRules.DAILY_CAP)
        assertEquals(rule("session_gap_s"), ModelRules.SESSION_GAP_S, 0.0)
        assertEquals(rule("t_max_h"), ModelRules.T_MAX_H.toDouble(), 0.0)
        assertEquals(rule("profile_days"), ModelRules.PROFILE_DAYS.toDouble(), 0.0)
        assertEquals(rule("p"), ModelRules.P, 0.0)
        assertEquals(rule("w_min"), ModelRules.W_MIN.toDouble(), 0.0)
    }

    @Test
    fun `priors match production json`() {
        val priors = json["population_prior"]!!.jsonObject
        for (pattern in Pattern.entries) {
            val p = priors[pattern.name]!!.jsonObject
            val n0 = p["n0_points"]!!.jsonObject
            assertEquals(
                Prior(
                    pf = p["pf"]!!.jsonPrimitive.double,
                    ps = p["ps"]!!.jsonPrimitive.double,
                    n0Pf = n0["pf"]!!.jsonPrimitive.double,
                    n0Ps = n0["ps"]!!.jsonPrimitive.double,
                ),
                ModelRules.PRIOR[pattern],
            )
        }
    }
}
