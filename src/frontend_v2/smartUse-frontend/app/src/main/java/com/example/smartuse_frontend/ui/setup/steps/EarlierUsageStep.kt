package com.example.smartuse_frontend.ui.setup.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_ACCEPT
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_REJECT
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_TEXT
import com.example.smartuse_frontend.ui.setup.EARLIER_USAGE_TITLE

/** Optional consent to read earlier usage; both answers look the same and declining costs nothing. */
@Composable
fun EarlierUsageStep(enabled: Boolean, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(EARLIER_USAGE_TITLE)
        Text(EARLIER_USAGE_TEXT)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onReject, enabled = enabled, modifier = Modifier.weight(1f)) { Text(EARLIER_USAGE_REJECT) }
            OutlinedButton(onClick = onAccept, enabled = enabled, modifier = Modifier.weight(1f)) { Text(EARLIER_USAGE_ACCEPT) }
        }
    }
}
