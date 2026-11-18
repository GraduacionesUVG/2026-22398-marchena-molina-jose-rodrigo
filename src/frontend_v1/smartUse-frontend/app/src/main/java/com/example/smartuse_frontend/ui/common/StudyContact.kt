package com.example.smartuse_frontend.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.BuildConfig

/**
 * Study support and information contact. Set at build time from
 * `smartuse.contact.phone` / `smartuse.contact.email` in local.properties
 * (see app/build.gradle.kts), so it never lives in source.
 */
object StudyContact {
    const val PHONE = BuildConfig.STUDY_CONTACT_PHONE
    const val EMAIL = BuildConfig.STUDY_CONTACT_EMAIL
}

/**
 * Support contact block. Selectable so participants can copy it before
 * uninstalling — after that, it's their only way to reach the study.
 */
@Composable
fun StudyContactInfo(
    modifier: Modifier = Modifier,
    heading: String = "Soporte e información del estudio",
) {
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge)
            Text("Teléfono: ${StudyContact.PHONE}", style = MaterialTheme.typography.bodySmall)
            Text("Correo: ${StudyContact.EMAIL}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
