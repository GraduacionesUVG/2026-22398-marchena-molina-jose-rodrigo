package com.example.smartuse_frontend.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.theme.AppShapes

@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    // Chart-style cards use a real heading; the default small label suits the
    // plain info cards (profile, opt-out).
    headline: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, AppShapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = if (headline) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelMedium,
            color = if (headline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}
