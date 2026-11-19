package com.example.smartuse_frontend.finalsend

import com.example.smartuse_frontend.forms.CompletedForm
import com.example.smartuse_frontend.forms.CompletedForms
import com.example.smartuse_frontend.metrics.DecisionKind
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.appJson
import com.example.smartuse_frontend.study.EndReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class FinalSendBuilderTest {

    private val forms = CompletedForms(
        listOf(
            CompletedForm("usability", 2, buildJsonObject { put("d1_1", 5); put("d12_1", "bien") }),
        ),
    )

    private fun encode(body: FinalSendRequest) = appJson.encodeToString(FinalSendRequest.serializer(), body)

    @Test
    fun `the body has the contract's shape - decision_point, nulls omitted, no ids`() {
        val body = FinalSendBuilder.build(
            EndReason.COMPLETED,
            forms,
            listOf(
                DecisionMetric(DecisionKind.SCREEN_ON, 412, 0, -310000, 98304),
                DecisionMetric(DecisionKind.ACTIVE_USE, 35, null, null, null),
            ),
            Random(1),
        )

        val metrics = body.metrics.joinToString(",") {
            if (it.decisionPoint == "screen_on") {
                """{"decision_point":"screen_on","duration_ms":412,"battery_delta_pct":0,"current_mean":-310000,"peak_rss_kb":98304}"""
            } else {
                """{"decision_point":"active_use","duration_ms":35}"""
            }
        }
        val expected = Json.parseToJsonElement(
            """{"end_reason":"completed",
                "forms":{"usability":{"version":2,"answers":{"d1_1":5,"d12_1":"bien"}}},
                "metrics":[$metrics]}""",
        )
        assertEquals(expected, Json.parseToJsonElement(encode(body)))
    }

    @Test
    fun `an opt-out is sent as opt_out`() {
        assertEquals("opt_out", FinalSendBuilder.build(EndReason.OPT_OUT, forms, emptyList(), Random(1)).endReason)
    }

    @Test
    fun `every metric is sent, in an order unrelated to the insertion order`() {
        val metrics = (0 until 20).map { DecisionMetric(DecisionKind.ACTIVE_USE, it.toLong(), null, null, null) }

        val sent = FinalSendBuilder.build(EndReason.COMPLETED, forms, metrics, Random(42)).metrics

        assertEquals(metrics.map { it.durationMs }.sorted(), sent.map { it.durationMs }.sorted())
        assertNotEquals(metrics.map { it.durationMs }, sent.map { it.durationMs })
    }
}