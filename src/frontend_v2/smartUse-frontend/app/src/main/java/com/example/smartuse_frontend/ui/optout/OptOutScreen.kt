package com.example.smartuse_frontend.ui.optout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.common.AppTopBar
import com.example.smartuse_frontend.ui.common.ConfirmationDialog
import com.example.smartuse_frontend.ui.common.InfoCard
import com.example.smartuse_frontend.ui.common.PageIntro

/** Once the opt-out is confirmed, MainActivity swaps the whole app for the end forms. */
@Composable
fun OptOutScreen(viewModel: OptOutViewModel, onBack: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { AppTopBar(title = "Retirarse del estudio", onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageIntro("Puedes dejar el estudio en cualquier momento.")

            InfoCard(title = "Retirarse del estudio") {
                Text(
                    "La aplicación deja de registrar el uso de tu teléfono. Luego te pediremos completar los formularios finales, que son parte de tu participación.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { confirming = true },
                    enabled = !viewModel.inFlight,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Retirarme") }
            }

            if (viewModel.inFlight) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Procesando tu retiro…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (confirming) {
            ConfirmationDialog(
                title = "Confirmar retiro",
                message = "La aplicación dejará de registrar el uso de tu teléfono y te pedirá completar los formularios finales. Esta acción no se puede deshacer. ¿Continuar?",
                confirmLabel = "Confirmar",
                isDestructive = true,
                onConfirm = {
                    viewModel.optOut()
                    confirming = false
                },
                onDismiss = { confirming = false },
            )
        }
    }
}
