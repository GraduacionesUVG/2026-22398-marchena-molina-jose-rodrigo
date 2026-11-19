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
import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.config.ExperimentGroupConfig
import com.example.smartuse_frontend.data.SetupDraft

// This app only onboards the data-collection (control) group. The SAS-SV score and the disguised group-selector
// ("Mes de verificación") are no longer participant-facing fields; the
// values SetupDraft/the backend still require are filled with fixtures here
// instead of asked for, so nothing downstream needs to change.
private val CONTROL_MONTH_OPTION = ExperimentGroupConfig.options.first { it.second == ExperimentGroup.CONTROL }.first
private const val FIXTURE_SAS_SV_SCORE = 0

@Composable
fun ProfileFormStep(onSubmit: (SetupDraft) -> Unit) {
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var major by remember { mutableStateOf("") }

    val ageInt = age.toIntOrNull()
    val valid = ageInt != null && ageInt in 16..99 && gender.isNotBlank() && major.isNotBlank()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Datos del participante")
        OutlinedTextField(age, { age = it.filter(Char::isDigit) }, label = { Text("Edad") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gender, { gender = it }, label = { Text("Género") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(major, { major = it }, label = { Text("Carrera universitaria") }, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                onSubmit(
                    SetupDraft(
                        age = ageInt ?: 0, gender = gender.trim(),
                        universityMajor = major.trim(), sasSvScore = FIXTURE_SAS_SV_SCORE,
                        monthOption = CONTROL_MONTH_OPTION,
                    ),
                )
            },
            enabled = valid, modifier = Modifier.fillMaxWidth(),
        ) { Text("Continuar") }
    }
}
