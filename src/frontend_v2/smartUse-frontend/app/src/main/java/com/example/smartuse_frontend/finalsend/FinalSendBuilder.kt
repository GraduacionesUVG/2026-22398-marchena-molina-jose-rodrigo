package com.example.smartuse_frontend.finalsend

import com.example.smartuse_frontend.forms.CompletedForms
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.network.dto.FinalForm
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.MetricRecord
import com.example.smartuse_frontend.study.EndReason
import kotlin.random.Random

/**
 * The POST /final body, built at each attempt: the frozen forms and every
 * metric record, shuffled and without ids (their order would be a coarse
 * usage timeline).
 */
object FinalSendBuilder {
    fun build(reason: EndReason, forms: CompletedForms, metrics: List<DecisionMetric>, random: Random): FinalSendRequest =
        FinalSendRequest(
            endReason = reason.wire,
            forms = forms.forms.associate { it.id to FinalForm(it.version, it.answers) },
            metrics = metrics.shuffled(random).map {
                MetricRecord(it.decisionPoint.wire, it.durationMs, it.batteryDeltaPct, it.currentMean, it.peakRssKb)
            },
        )
}