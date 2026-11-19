package com.example.smartuse_frontend.ui.setup.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.terms.TERMS_ACCEPTANCE
import com.example.smartuse_frontend.ui.terms.TermsDocument

@Composable
fun TermsStep(onAccept: () -> Unit) {
    val scrollState = rememberScrollState()
    val scrolledToEnd by remember {
        derivedStateOf { scrollState.value >= scrollState.maxValue - 1 }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TermsDocument()
            Text(TERMS_ACCEPTANCE, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onAccept, enabled = scrolledToEnd, modifier = Modifier.fillMaxWidth()) {
            Text("Acepto y continúo")
        }
    }
}
