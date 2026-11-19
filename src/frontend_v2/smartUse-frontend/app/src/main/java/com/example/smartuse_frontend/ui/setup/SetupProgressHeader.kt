package com.example.smartuse_frontend.ui.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "Paso X de Y" with a bar, shown above each numbered wizard step. */
@Composable
fun SetupProgressHeader(progress: SetupProgress, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        targetValue = progress.current.toFloat() / progress.total,
        label = "setupProgress",
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Paso ${progress.current} de ${progress.total}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}
