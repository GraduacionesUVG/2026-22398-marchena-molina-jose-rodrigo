package com.example.smartuse_frontend.ui.dataoverview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.BuildConfig
import com.example.smartuse_frontend.data.EpochRecord
import com.example.smartuse_frontend.data.ParticipantProfile
import com.example.smartuse_frontend.data.db.PipelineStageStatus
import com.example.smartuse_frontend.data.db.buildRawLogShareIntent
import com.example.smartuse_frontend.data.db.exportRawLogAsCsv
import com.example.smartuse_frontend.data.db.printCanonicalDb
import com.example.smartuse_frontend.data.db.printRawCaptureLog
import com.example.smartuse_frontend.data.db.readCanonicalStatus
import com.example.smartuse_frontend.data.db.readRawLogStatus
import com.example.smartuse_frontend.data.db.runFullPipeline
import com.example.smartuse_frontend.inference.DecisionRuntime
import com.example.smartuse_frontend.inference.debugProfileLabel
import com.example.smartuse_frontend.intervention.InterventionRuntime
import com.example.smartuse_frontend.intervention.OverlayCue
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.metrics.MetricsDbHelper
import com.example.smartuse_frontend.metrics.SqlMetricsStore
import com.example.smartuse_frontend.ui.common.AppTopBar
import com.example.smartuse_frontend.ui.common.DebugUi
import com.example.smartuse_frontend.ui.common.ExpandableInfo
import com.example.smartuse_frontend.ui.common.InfoCard
import com.example.smartuse_frontend.ui.common.PageIntro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DataOverviewScreen(viewModel: DataOverviewViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = { AppTopBar(title = "Resumen de datos", onBack = onBack) },
    ) { innerPadding ->
        when (val state = viewModel.uiState) {
            is DataOverviewUiState.Loading -> Text("Cargando…", modifier = Modifier.padding(innerPadding))
            is DataOverviewUiState.Error -> Text(state.message, modifier = Modifier.padding(innerPadding))
            is DataOverviewUiState.Success -> DataOverviewContent(
                state = state,
                onDateSelected = viewModel::onDateSelected,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

private val TAB_TITLES = listOf("Perfil", "Uso")

@Composable
private fun DataOverviewContent(
    state: DataOverviewUiState.Success,
    onDateSelected: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        PageIntro(
            "Consulta tu perfil y un resumen diario del uso de tu teléfono. Las aplicaciones nunca " +
                "se identifican por su nombre.",
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        )
        SecondaryTabRow(selectedTabIndex = selectedTab) {
            TAB_TITLES.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }
        when (selectedTab) {
            0 -> ProfileTab(profile = state.profile, userId = state.userId)
            1 -> UsageTab(state = state, onDateSelected = onDateSelected, onRefresh = onRefresh)
        }
    }
}

// --- Tab 1: profile + pipeline debug -----------------------------------

@Composable
private fun ProfileTab(profile: ParticipantProfile, userId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawLogStatus by remember { mutableStateOf<PipelineStageStatus?>(null) }
    var canonicalStatus by remember { mutableStateOf<PipelineStageStatus?>(null) }
    var metricsCount by remember { mutableStateOf<Int?>(null) }
    var profileLabel by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            rawLogStatus = readRawLogStatus(context)
            canonicalStatus = readCanonicalStatus(context)
            metricsCount = SqlMetricsStore(MetricsDbHelper(context)).count()
            profileLabel = debugProfileLabel(context)
        }
    }

    LaunchedEffect(Unit) { if (BuildConfig.DEBUG) refreshStatus() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InfoCard(title = "Tu perfil") {
                Text("ID de usuario: ${userId ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Edad: ${profile.age}", style = MaterialTheme.typography.bodyMedium)
                Text("Género: ${profile.gender}", style = MaterialTheme.typography.bodyMedium)
                Text("Carrera: ${profile.universityMajor}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Developer tools: debug builds only. In release BuildConfig.DEBUG is a
        // constant false, so R8 removes this code and the debug helpers.
        if (BuildConfig.DEBUG && DebugUi.showTools) {
            item {
                InfoCard(title = "[DEBUG] Estado de las etapas del pipeline") {
                    Text(
                        "Registro sin procesar: ${rawLogStatus?.rowCount ?: "…"} filas — " +
                            "${rawLogStatus?.cursorLabel}=${rawLogStatus?.cursorValue ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "DB canónica: ${canonicalStatus?.rowCount ?: "…"} épocas — " +
                            "${canonicalStatus?.cursorLabel}=${canonicalStatus?.cursorValue ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Métricas: ${metricsCount ?: "…"} puntos de decisión registrados",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Perfil: ${profileLabel ?: "…"}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Programación: ${JobLimits.current.describe()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { printCanonicalDb(context) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("[DEBUG] Imprimir DB canónica") }
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { printRawCaptureLog(context) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("[DEBUG] Imprimir log crudo") }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                runFullPipeline(context)
                                withContext(Dispatchers.Main) { refreshStatus() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("[DEBUG] Ejecutar pipeline completo") }
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { JobRuntime.refreshRecordingNotice(context) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("[DEBUG] Actualizar notificación") }
                    Button(
                        onClick = { InterventionRuntime.coordinator(context).onIntervention(OverlayCue.F) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("[DEBUG] Mostrar intervención") }
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { DecisionRuntime.runner(context).runNow() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("[DEBUG] Ejecutar inferencia") }
                }
            }
        }
    }
}

// --- Tab 2: charts -------------------------------------------------------

@Composable
private fun UsageTab(
    state: DataOverviewUiState.Success,
    onDateSelected: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useEpochs = state.epochs.filter { it.isUse }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            InfoCard(title = "Captura de datos") {
                Text(
                    state.signupDate?.let {
                        "Captura de datos iniciada el " +
                            it.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es")))
                    } ?: "Fecha de inicio de captura no disponible",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    DateSelector(
                        selectedDate = state.selectedDate,
                        minDate = state.signupDate ?: state.selectedDate,
                        maxDate = LocalDate.now(),
                        onDateSelected = onDateSelected,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
                }
            }
        }
        item {
            InfoCard(title = "Épocas de Uso/No-Uso", headline = true) {
                TimelineChart(epochs = state.epochs, day = state.selectedDate)
                ExpandableInfo(
                    text = "Esta gráfica muestra, para el día seleccionado, los períodos de uso (verde) y de " +
                        "no uso (azul) del dispositivo detectados entre las 00:00 y las 23:59. Los tramos " +
                        "sin color no han sido capturados o procesados todavía.",
                )
            }
        }
        item {
            InfoCard(title = "Apps distintas por período de uso", headline = true) {
                AppCountChart(useEpochs = useEpochs)
                ExpandableInfo(
                    text = "Esta gráfica muestra, para cada período de uso del día seleccionado, cuántas " +
                        "aplicaciones distintas (identificadas de forma anónima, nunca por su nombre) se " +
                        "usaron. Cada barra representa un período de uso completo; su ancho no refleja la " +
                        "duración real del período.",
                )
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { exportRawLogAsCsv(context) }
                        context.startActivity(buildRawLogShareIntent(context, file))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Exportar registro sin procesar (CSV)") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelector(
    selectedDate: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selectedDate.format(DateTimeFormatter.ofPattern("EEEE d MMM yyyy", Locale.forLanguageTag("es"))))
    }

    if (showPicker) {
        val minMillis = minDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val maxMillis = maxDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis in minMillis..maxMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = state)
        }
    }
}
