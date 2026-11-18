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
import com.example.smartuse_frontend.study.OPTED_OUT_TEXT
import com.example.smartuse_frontend.study.SENDING_TEXT
import com.example.smartuse_frontend.study.SENDING_TITLE
import com.example.smartuse_frontend.study.THANKS_TITLE
import com.example.smartuse_frontend.ui.common.StudyContactInfo

/**
 * The end screen: "sending" while the final send is pending,
 * then the thanks. Never left (see MainActivity).
 */
@Composable
fun EndOfStudyScreen(sent: Boolean, optedOut: Boolean) {
    val context = LocalContext.current
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!sent) {
                Text(SENDING_TITLE, style = MaterialTheme.typography.titleLarge)
                Text(SENDING_TEXT, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(THANKS_TITLE, style = MaterialTheme.typography.titleLarge)
                Text(if (optedOut) OPTED_OUT_TEXT else END_OF_COLLECTION_TEXT, style = MaterialTheme.typography.bodyLarge)

                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:${context.packageName}".toUri()),
                        )
                    },
                ) { Text("Desinstalar ahora") }
            }

            StudyContactInfo(heading = "Preguntas o solicitudes sobre tus datos")
        }
    }
}