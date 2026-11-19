package com.example.smartuse_frontend.ui.optout

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.common.StudyContactInfo

/**
 * Reached once JobStateStore.hasOptedOut is true and never left — see
 * MainActivity. Follows the opt-out status live: for a hard opt-out it shows the
 * deletion status with a progress indicator (retries are automatic) and keeps
 * the uninstall shortcut disabled until the backend confirms; soft never
 * surfaces a status.
 */
@Composable
fun OptOutDoneScreen(viewModel: OptOutDoneViewModel) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (ui.canUninstall) {
                    "Retiro completado. Gracias por tu participación. Ya puedes desinstalar la aplicación."
                } else {
                    "Retiro completado. Gracias por tu participación."
                },
                style = MaterialTheme.typography.bodyLarge,
            )

            ui.statusLine?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ui.inProgress) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${context.packageName}")),
                    )
                },
                enabled = ui.canUninstall,
            ) { Text("Desinstalar ahora") }

            ui.uninstallHint?.let { hint ->
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            StudyContactInfo(heading = "Preguntas o solicitudes sobre tus datos")
        }
    }
}
