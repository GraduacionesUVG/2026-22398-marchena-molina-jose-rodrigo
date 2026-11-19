package com.example.smartuse_frontend.ui.setup.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.data.SetupDraft
import com.example.smartuse_frontend.ui.setup.SAS_SV_MAX_SCORE
import com.example.smartuse_frontend.ui.setup.SAS_SV_MIN_SCORE
import com.example.smartuse_frontend.ui.setup.parseSasSvScore

@Composable
fun ProfileFormStep(onSubmit: (SetupDraft) -> Unit) {
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var major by remember { mutableStateOf("") }
    // The SAS-SV screening score, taken outside the app (screening web app) before install.
    var score by remember { mutableStateOf("") }

    val ageInt = age.toIntOrNull()
    val scoreInt = parseSasSvScore(score)
    val scoreError = score.isNotEmpty() && scoreInt == null
    val valid = ageInt != null && ageInt in 16..99 && gender.isNotBlank() && major.isNotBlank() && scoreInt != null

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Datos del participante")
        OutlinedTextField(age, { age = it.filter(Char::isDigit) }, label = { Text("Edad") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gender, { gender = it }, label = { Text("Género") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(major, { major = it }, label = { Text("Carrera universitaria") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            score, { score = it.filter(Char::isDigit).take(2) },
            label = { Text("Puntaje SAS-SV") },
            supportingText = {
                Text(
                    if (scoreError) "Debe estar entre $SAS_SV_MIN_SCORE y $SAS_SV_MAX_SCORE"
                    else "El puntaje que te dio el cuestionario de selección.",
                )
            },
            isError = scoreError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                onSubmit(
                    SetupDraft(
                        age = ageInt ?: 0, gender = gender.trim(),
                        universityMajor = major.trim(), sasSvScore = scoreInt ?: 0,
                    ),
                )
            },
            enabled = valid, modifier = Modifier.fillMaxWidth(),
        ) { Text("Continuar") }
    }
}
