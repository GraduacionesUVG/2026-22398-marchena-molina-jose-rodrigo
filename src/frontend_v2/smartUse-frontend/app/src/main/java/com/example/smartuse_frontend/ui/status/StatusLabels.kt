package com.example.smartuse_frontend.ui.status

import com.example.smartuse_frontend.jobs.JobHealth
import com.example.smartuse_frontend.ui.common.StatusTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es"))

private fun format(instant: Instant, zone: ZoneId): String = dateTimeFormat.withZone(zone).format(instant)

internal fun captureDetail(health: JobHealth, zone: ZoneId = ZoneId.systemDefault()): String = when {
    health.stuckSince != null -> "Falla desde: ${format(health.stuckSince, zone)}"
    health.lastSuccessAt != null -> "Última ejecución: ${format(health.lastSuccessAt, zone)}"
    else -> "Aún no se ha ejecutado"
}

internal fun captureTone(health: JobHealth): StatusTone = when {
    health.stuckSince != null -> StatusTone.ERROR
    health.lastSuccessAt != null -> StatusTone.SUCCESS
    else -> StatusTone.NEUTRAL
}

/** The short pill text for a background job's state; the detail line carries the time. */
internal fun jobStateLabel(tone: StatusTone): String = when (tone) {
    StatusTone.SUCCESS -> "Al día"
    StatusTone.ERROR -> "Con fallas"
    StatusTone.NEUTRAL -> "Pendiente"
}
