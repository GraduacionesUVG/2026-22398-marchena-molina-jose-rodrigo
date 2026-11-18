package com.example.smartuse_frontend.ui.dataoverview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.data.EpochRecord
import com.example.smartuse_frontend.ui.theme.AppShapes
import java.time.LocalDate
import java.time.ZoneId

// Deliberate exception to DESIGN.md's "only one red in this system" rule —
// the design-system-compliant green/blue pairing read as too similar in
// tone to reliably distinguish at a glance, so this chart specifically uses
// these two hex values instead of design tokens.
private val TimelineUseColor = Color(0xFF99A0F3)
private val TimelineNonUseColor = Color(0xFFFF6C6C)

// A single 24h bar for the selected day: unfilled = not captured/processed
// yet. Deliberately hand-rolled with Canvas rather than a chart library —
// see the conversation history for why (custom shape, no real chart-library
// value-add here).
@Composable
fun TimelineChart(
    epochs: List<EpochRecord>,
    day: LocalDate,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val dayLengthMs = (dayEnd - dayStart).toFloat()

    val useColor = TimelineUseColor
    val nonUseColor = TimelineNonUseColor
    val trackColor = MaterialTheme.colorScheme.surfaceContainer

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(AppShapes.small),
        ) {
            drawRect(color = trackColor)
            epochs.forEach { epoch ->
                val clippedStart = epoch.startTs.coerceIn(dayStart, dayEnd)
                val clippedEnd = epoch.endTs.coerceIn(dayStart, dayEnd)
                if (clippedEnd <= clippedStart) return@forEach
                val left = (clippedStart - dayStart) / dayLengthMs * size.width
                val right = (clippedEnd - dayStart) / dayLengthMs * size.width
                drawRect(
                    color = if (epoch.isUse) useColor else nonUseColor,
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("00:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("23:59", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            LegendDot(useColor, "Uso")
            Spacer(Modifier.width(16.dp))
            LegendDot(nonUseColor, "Sin uso")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
