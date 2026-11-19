package com.example.smartuse_frontend.ui.dataoverview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.data.EpochRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Distinct-app count per use epoch for the selected day, as equal-width bars
// (real epoch durations vary, but bars are drawn at equal width on purpose —
// see the design discussion — so each use epoch reads as one comparable
// unit). Y axis = the count; x axis labels = each bar's epoch end time
// (HH:mm), since that's the moment the following non-use epoch begins.
@Composable
fun AppCountChart(
    useEpochs: List<EpochRecord>,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val barColor = MaterialTheme.colorScheme.primaryContainer
    val axisColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val timeFormatter = remember(zoneId) { DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId) }

    Column(modifier) {
        if (useEpochs.isEmpty()) {
            Text(
                "No hay épocas de uso este día.",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
        } else {
            val maxCount = useEpochs.maxOf { it.distinctAppCount }.coerceAtLeast(1)

            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val yAxisLabelWidth = textMeasurer.measure(maxCount.toString(), labelStyle).size.width.toFloat()
                val leftMargin = yAxisLabelWidth + 8.dp.toPx()
                val bottomMargin = 24.dp.toPx()
                val plotWidth = size.width - leftMargin
                val plotHeight = size.height - bottomMargin

                // Y axis: line, and 0 / mid / max tick labels with faint gridlines.
                drawLine(axisColor, Offset(leftMargin, 0f), Offset(leftMargin, plotHeight))
                listOf(0, maxCount / 2, maxCount).distinct().forEach { tick ->
                    val y = plotHeight - (tick.toFloat() / maxCount) * plotHeight
                    drawLine(gridColor, Offset(leftMargin, y), Offset(size.width, y))
                    val label = textMeasurer.measure(tick.toString(), labelStyle)
                    drawText(label, topLeft = Offset(leftMargin - label.size.width - 8.dp.toPx(), y - label.size.height / 2))
                }

                // Bars + x-axis end-time labels, equal width regardless of real duration.
                val barCount = useEpochs.size
                val gap = plotWidth * 0.02f
                val barWidth = (plotWidth - gap * (barCount - 1).coerceAtLeast(0)) / barCount
                useEpochs.forEachIndexed { index, epoch ->
                    val barHeight = (epoch.distinctAppCount.toFloat() / maxCount) * plotHeight
                    val left = leftMargin + index * (barWidth + gap)
                    drawRect(
                        color = barColor,
                        topLeft = Offset(left, plotHeight - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                    val endLabel = textMeasurer.measure(timeFormatter.format(Instant.ofEpochMilli(epoch.endTs)), labelStyle)
                    drawText(
                        endLabel,
                        topLeft = Offset(left + barWidth / 2 - endLabel.size.width / 2, plotHeight + 4.dp.toPx()),
                    )
                }
                drawLine(axisColor, Offset(leftMargin, plotHeight), Offset(size.width, plotHeight))
            }
        }
    }
}
