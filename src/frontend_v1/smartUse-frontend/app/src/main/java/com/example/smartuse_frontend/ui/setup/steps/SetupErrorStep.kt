package com.example.smartuse_frontend.ui.setup.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared error screen. [onBackToForm] is null for the Keystore failure
 * (nothing in the form to change) and non-null for registration failures.
 */
@Composable
fun SetupErrorStep(
    message: String,
    onRetry: () -> Unit,
    onBackToForm: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Reintentar") }
        if (onBackToForm != null) {
            OutlinedButton(onClick = onBackToForm, modifier = Modifier.fillMaxWidth()) {
                Text("Volver al formulario")
            }
        }
    }
}
