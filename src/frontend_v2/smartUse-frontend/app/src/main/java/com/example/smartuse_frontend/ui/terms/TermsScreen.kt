package com.example.smartuse_frontend.ui.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.ui.common.AppTopBar
import com.example.smartuse_frontend.ui.common.PageIntro

@Composable
fun TermsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { AppTopBar(title = "Términos y condiciones", onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageIntro("El texto que aceptaste al unirte al estudio. Puedes revisarlo cuando quieras.")
            TermsDocument()
        }
    }
}
