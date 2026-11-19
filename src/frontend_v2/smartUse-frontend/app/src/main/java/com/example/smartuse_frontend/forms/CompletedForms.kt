package com.example.smartuse_frontend.forms

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One submitted form, frozen: what POST /final sends for it. */
@Serializable
data class CompletedForm(val id: String, val version: Int, val answers: JsonObject)

/** The frozen copy "Enviar" stores; kept until the final send is confirmed. */
@Serializable
data class CompletedForms(val forms: List<CompletedForm>)

/** Each form's valid answers. */
fun freezeForms(definitions: List<FormDefinition>, answers: Map<String, Map<String, FormAnswer>>): CompletedForms =
    CompletedForms(
        definitions.map { form ->
            CompletedForm(form.id, form.version, form.validAnswers(answers[form.id].orEmpty()).toJson())
        },
    )