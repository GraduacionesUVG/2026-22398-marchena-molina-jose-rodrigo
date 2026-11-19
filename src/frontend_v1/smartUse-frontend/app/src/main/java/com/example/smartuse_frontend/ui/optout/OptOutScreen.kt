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

/**
 * Only the "not opted out yet" state — once either action is confirmed,
 * MainActivity swaps this whole screen out for OptOutDoneScreen (see
 * JobStateStore.hasOptedOut), so no post-action state is tracked here.
 */
@Composable
fun OptOutScreen(viewModel: OptOutViewModel, onBack: () -> Unit) {
    var pendingConfirmation by remember { mutableStateOf<OptOutKind?>(null) }

    Scaffold(
        topBar = { AppTopBar(title = "Retirarse del estudio", onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageIntro(
                "Puedes dejar el estudio en cualquier momento. Elige si se conservan o se eliminan " +
                    "los datos que ya se enviaron.",
            )

            InfoCard(title = "Retiro parcial") {
                Text(
                    "Detiene toda futura captura y sincronización de datos. Los datos ya " +
                        "recopilados se conservan y siguen contribuyendo al estudio.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { pendingConfirmation = OptOutKind.SOFT },
                    enabled = !viewModel.inFlight,
                ) { Text("Retiro parcial") }
            }

            InfoCard(title = "Retiro total") {
                Text(
                    "Detiene toda futura captura y sincronización de datos, y elimina los " +
                        "datos ya recopilados del servidor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { pendingConfirmation = OptOutKind.HARD },
                    enabled = !viewModel.inFlight,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Retiro total") }
            }

            if (viewModel.inFlight) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Procesando tu retiro…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        pendingConfirmation?.let { kind ->
            ConfirmationDialog(
                title = if (kind == OptOutKind.SOFT) "Confirmar retiro parcial" else "Confirmar retiro total",
                message = if (kind == OptOutKind.SOFT) {
                    "Dejarás de contribuir con datos nuevos. Los datos ya sincronizados se conservan. ¿Continuar?"
                } else {
                    "Dejarás de contribuir con datos nuevos y tus datos ya sincronizados " +
                        "serán eliminados. Esta acción no se puede deshacer. ¿Continuar?"
                },
                confirmLabel = "Confirmar",
                isDestructive = kind == OptOutKind.HARD,
                onConfirm = {
                    viewModel.optOut(kind)
                    pendingConfirmation = null
                },
                onDismiss = { pendingConfirmation = null },
            )
        }
    }
}
