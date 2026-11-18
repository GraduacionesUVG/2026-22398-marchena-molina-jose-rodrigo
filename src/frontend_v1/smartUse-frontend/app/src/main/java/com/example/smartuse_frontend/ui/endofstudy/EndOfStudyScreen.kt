package com.example.smartuse_frontend.ui.endofstudy

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.smartuse_frontend.study.END_OF_COLLECTION_TEXT
import com.example.smartuse_frontend.ui.common.StudyContactInfo

/**
 * Reached once JobStateStore.collectionEnded is true and never left (see
 * MainActivity). Local data is already erased; there is no in-app withdrawal
 * after the end, so requests go through the researcher (terms §8).
 */
@Composable
fun EndOfStudyScreen() {
    val context = LocalContext.current
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(END_OF_COLLECTION_TEXT, style = MaterialTheme.typography.bodyLarge)

            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:${context.packageName}".toUri()),
                    )
                },
            ) { Text("Desinstalar ahora") }

            StudyContactInfo(heading = "Preguntas o solicitudes sobre tus datos")
        }
    }
}
