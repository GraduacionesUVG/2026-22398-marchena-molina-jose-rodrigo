package com.example.smartuse_frontend.forms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One answer: a choice's value or free text, sent as a JSON integer or string. */
sealed interface FormAnswer {
    data class Choice(val value: Int) : FormAnswer
    data class Text(val value: String) : FormAnswer
}

/** Whether [answer] answers this question validly; blank text is no answer. */
fun Question.accepts(answer: FormAnswer?): Boolean = when (val k = kind) {
    is QuestionKind.SingleChoice -> answer is FormAnswer.Choice && k.options.any { it.value == answer.value }
    is QuestionKind.OpenText -> answer is FormAnswer.Text && answer.value.isNotBlank() && answer.value.length <= k.maxLength
}

/** At most [maxLength] UTF-16 units, never half an emoji (the backend would store it as "�"). */
fun String.takeWhole(maxLength: Int): String {
    if (length <= maxLength) return this
    val cut = take(maxLength)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** Every required question of these has a valid answer. */
fun List<Question>.areComplete(answers: Map<String, FormAnswer>): Boolean =
    filter { it.required }.all { it.accepts(answers[it.id]) }

fun FormDefinition.isComplete(answers: Map<String, FormAnswer>): Boolean = questions.areComplete(answers)

/** Only the valid answers to this form's own questions. */
fun FormDefinition.validAnswers(answers: Map<String, FormAnswer>): Map<String, FormAnswer> =
    questions.mapNotNull { q -> answers[q.id]?.takeIf(q::accepts)?.let { q.id to it } }.toMap()

/** As POST /final sends them: question id → integer or string. */
fun Map<String, FormAnswer>.toJson(): JsonObject = buildJsonObject {
    for ((id, answer) in this@toJson) {
        when (answer) {
            is FormAnswer.Choice -> put(id, answer.value)
            is FormAnswer.Text -> put(id, answer.value)
        }
    }
}

/** The inverse of [toJson]; anything else is skipped. */
fun JsonObject.toAnswers(): Map<String, FormAnswer> = mapNotNull { (id, value) ->
    val primitive = value as? JsonPrimitive ?: return@mapNotNull null
    when {
        primitive.isString -> id to FormAnswer.Text(primitive.content)
        else -> primitive.intOrNull?.let { id to FormAnswer.Choice(it) }
    }
}.toMap()