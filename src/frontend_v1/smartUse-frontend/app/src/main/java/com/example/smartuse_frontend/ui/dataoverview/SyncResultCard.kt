package com.example.smartuse_frontend.ui.dataoverview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
import com.example.smartuse_frontend.data.describeCursor
import com.example.smartuse_frontend.ui.theme.AppShapes
import com.example.smartuse_frontend.ui.theme.StatusSuccess
import java.time.ZoneId

internal data class SyncResultText(
    val isSuccess: Boolean,
    val headline: String,
    val details: List<String>,
)

internal fun syncResultText(
    outcome: CanonicalSyncOutcome,
    zone: ZoneId = ZoneId.systemDefault(),
): SyncResultText = when (outcome) {
    is CanonicalSyncOutcome.Success -> SyncResultText(
        isSuccess = true,
        headline = when {
            outcome.recordsSent == 0 -> "Nada nuevo por sincronizar"
            outcome.batches > 1 -> "Sincronizado: ${outcome.recordsSent} registro(s) en ${outcome.batches} lotes"
            else -> "Sincronizado: ${outcome.recordsSent} registro(s)"
        },
        details = buildList {
            add("Sincronizado hasta: ${describeCursor(outcome.cursorAfter, zone)}")
            add("Antes de este intento: ${describeCursor(outcome.cursorBefore, zone)}")
            if (outcome.hasMore) add("Quedan registros por enviar; continuarán en la próxima sincronización.")
            outcome.note?.let { add("Aviso: $it") }
        },
    )
    is CanonicalSyncOutcome.Failed -> SyncResultText(
        isSuccess = false,
        headline = "Error de sincronización",
        details = listOf(outcome.message),
    )
}

// TEMP — debug only, alongside the [DEBUG] sync button. Green on success
// (including "nothing new"), red on failure; the exact HTTP detail is in
// Logcat under tags SmartUseApi / CanonicalSync.
@Composable
fun SyncResultCard(outcome: CanonicalSyncOutcome, modifier: Modifier = Modifier) {
    val text = syncResultText(outcome)
    val accent: Color = if (text.isSuccess) StatusSuccess else MaterialTheme.colorScheme.error
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .border(1.dp, accent, AppShapes.small)
            .background(accent.copy(alpha = 0.10f))
            .padding(16.dp),
    ) {
        Text(text.headline, style = MaterialTheme.typography.titleSmall, color = accent)
        text.details.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
