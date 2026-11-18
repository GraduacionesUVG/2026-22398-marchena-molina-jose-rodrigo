package com.example.smartuse_frontend.ui.endforms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.smartuse_frontend.forms.FormAnswer
import com.example.smartuse_frontend.forms.Question
import com.example.smartuse_frontend.forms.QuestionKind
import com.example.smartuse_frontend.forms.takeWhole
import com.example.smartuse_frontend.ui.common.AppTopBar
import com.example.smartuse_frontend.ui.common.ConfirmationDialog
import com.example.smartuse_frontend.ui.common.StudyContactInfo
import com.example.smartuse_frontend.ui.terms.TermsScreen

/** Test tags: one row per choice option, one field per open question. */
fun choiceTag(questionId: String, value: Int) = "answer:$questionId:$value"
fun textTag(questionId: String) = "answer:$questionId"

const val END_FORMS_TITLE = "Formularios finales"
const val END_FORMS_PROGRESS_TAG = "end-forms:progress"
const val END_FORMS_NEXT = "Siguiente"
const val END_FORMS_SEND = "Enviar"
const val END_FORMS_CONFIRM = "Sí, enviar"

/** The app's root while the end forms are pending (AppRoute.END_FORMS). */
@Composable
fun EndFormsScreen(viewModel: EndFormsViewModel) {
    var confirming by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    if (showTerms) {
        BackHandler { showTerms = false }
        TermsScreen(onBack = { showTerms = false })
        return
    }
    BackHandler(enabled = viewModel.page > 0) { viewModel.back() }

    Scaffold(topBar = { AppTopBar(title = END_FORMS_TITLE) }) { innerPadding ->
        if (!viewModel.loaded) return@Scaffold
        val form = viewModel.currentForm
        val section = viewModel.currentSection
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // Progress over the sections, fixed above the scrolling page.
            LinearProgressIndicator(
                progress = { (viewModel.page + 1f) / viewModel.pageCount },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag(END_FORMS_PROGRESS_TAG),
            )
            // A new page starts at the top.
            key(viewModel.page) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Sección ${viewModel.page + 1} de ${viewModel.pageCount}", style = MaterialTheme.typography.labelLarge)
                    if (viewModel.isFormStart) {
                        Text(form.title, style = MaterialTheme.typography.titleLarge)
                        Text(form.intro, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    section.questions.forEach { question ->
                        QuestionCard(question, viewModel.answerTo(question.id)) { viewModel.answer(question.id, it) }
                    }
                    if (viewModel.isLastPage) {
                        Button(
                            onClick = { confirming = true },
                            enabled = viewModel.canContinue && !viewModel.submitting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(END_FORMS_SEND) }
                    } else {
                        Button(onClick = viewModel::next, enabled = viewModel.canContinue, modifier = Modifier.fillMaxWidth()) {
                            Text(END_FORMS_NEXT)
                        }
                    }
                    if (viewModel.page > 0) TextButton(onClick = viewModel::back) { Text("Anterior") }
                    TextButton(onClick = { showTerms = true }) { Text("Términos y condiciones") }
                    StudyContactInfo()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirming) {
        ConfirmationDialog(
            title = "Enviar respuestas",
            message = "Después de enviar no podrás cambiar tus respuestas.",
            confirmLabel = END_FORMS_CONFIRM,
            onConfirm = {
                confirming = false
                viewModel.submit()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun QuestionCard(question: Question, answer: FormAnswer?, onAnswer: (FormAnswer?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question.text, style = MaterialTheme.typography.bodyLarge)
            when (val kind = question.kind) {
                is QuestionKind.SingleChoice -> Column(Modifier.selectableGroup()) {
                    kind.options.forEach { option ->
                        val selected = (answer as? FormAnswer.Choice)?.value == option.value
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected, role = Role.RadioButton) { onAnswer(FormAnswer.Choice(option.value)) }
                                .testTag(choiceTag(question.id, option.value)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(option.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                is QuestionKind.OpenText -> {
                    val text = (answer as? FormAnswer.Text)?.value.orEmpty()
                    OutlinedTextField(
                        value = text,
                        onValueChange = { onAnswer(FormAnswer.Text(it.takeWhole(kind.maxLength))) },
                        minLines = 3,
                        supportingText = { Text("${text.length}/${kind.maxLength}") },
                        modifier = Modifier.fillMaxWidth().testTag(textTag(question.id)),
                    )
                }
            }
        }
    }
}