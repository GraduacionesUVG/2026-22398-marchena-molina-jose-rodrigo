package com.example.smartuse_frontend.forms

/** One option of a single-choice question: the label shown and the integer sent. */
data class Option(val value: Int, val label: String)

/** How a question is answered. */
sealed interface QuestionKind {
    /** One of [options], shown as a list. */
    data class SingleChoice(val options: List<Option>) : QuestionKind

    /** Free text, at most [maxLength] characters. */
    data class OpenText(val maxLength: Int = 1000) : QuestionKind
}

data class Question(val id: String, val text: String, val kind: QuestionKind, val required: Boolean)

/** One page of a form: a title and all its questions. */
data class FormSection(val title: String, val questions: List<Question>)

/** One end form; [version] changes whenever its questions do, and is sent with the answers. */
data class FormDefinition(
    val id: String,
    val version: Int,
    val title: String,
    val intro: String,
    val sections: List<FormSection>,
) {
    val questions: List<Question> get() = sections.flatMap { it.questions }
}

/**
 * The end forms: only the usability questionnaire, one page
 * per dimension, with the researcher's wording. Ids `dN_M` follow its
 * numbering; choices are sent as the listed order 1–5 (Sí/No as 1/0).
 * Changing a question means editing this file and bumping `version`.
 */
object FormDefinitions {
    const val USABILITY_ID = "usability"

    private fun options(vararg labels: String) = labels.mapIndexed { i, label -> Option(i + 1, label) }

    private val AGREEMENT = options(
        "(1) Totalmente en desacuerdo",
        "(2) En desacuerdo",
        "(3) Ni de acuerdo ni en desacuerdo",
        "(4) De acuerdo",
        "(5) Totalmente de acuerdo",
    )
    private val HOW_OFTEN = options("Nunca", "Rara vez", "A veces", "Frecuentemente", "Siempre")

    private fun choice(id: String, text: String, options: List<Option> = AGREEMENT) =
        Question(id, text, QuestionKind.SingleChoice(options), required = true)

    private fun open(id: String, text: String) = Question(id, text, QuestionKind.OpenText(), required = false)

    val USABILITY = FormDefinition(
        id = USABILITY_ID,
        version = 2,
        title = "Tu experiencia con SmartUse",
        intro = "Cuéntanos cómo fue usar la aplicación durante el estudio.",
        sections = listOf(
            FormSection(
                "Comprensión del propósito de la intervención",
                listOf(
                    choice("d1_1", "El texto de la intervención visual dejaba claro que venía del estudio SmartUse y para qué servía."),
                    choice("d1_2", "Entendí qué tipo de uso (revisiones frecuentes o uso prolongado sin pausa) motivó cada pausa."),
                ),
            ),
            FormSection(
                "Pertinencia percibida del momento de activación",
                listOf(
                    choice("d2_1", "Las intervenciones visuales por revisiones frecuentes aparecieron en momentos en que estaba revisando el teléfono una y otra vez."),
                    choice("d2_2", "Las intervenciones visuales por uso prolongado sin pausa aparecieron en momentos en que llevaba más tiempo del que quería."),
                    choice(
                        "d2_3",
                        "¿Con qué frecuencia sentiste que la interrupción ocurrió en un momento inapropiado, por ejemplo, mientras usabas el teléfono de forma intencional y planificada?",
                        HOW_OFTEN,
                    ),
                ),
            ),
            FormSection(
                "Frecuencia e intensidad de la fricción visual",
                listOf(
                    choice(
                        "d3_1",
                        "¿Con qué frecuencia consideras que la aplicación interrumpió tu uso del teléfono durante el periodo de estudio?",
                        options("Muy poco frecuente", "Poco frecuente", "Frecuencia adecuada", "Frecuente", "Muy frecuente"),
                    ),
                    choice("d3_2", "La intensidad visual de la interrupción fue adecuada: ni insuficiente ni excesiva."),
                ),
            ),
            FormSection(
                "Duración del conteo regresivo",
                listOf(
                    choice(
                        "d4_1",
                        "¿Cómo calificarías la duración del conteo regresivo?",
                        options("Muy corta", "Corta", "Adecuada", "Larga", "Muy larga"),
                    ),
                ),
            ),
            FormSection(
                "Respuesta afectiva y reflexiva",
                listOf(
                    choice("d5_1", "La interrupción me generó una sensación de molestia o frustración."),
                    choice("d5_2", "La intervención visual me llevó a reflexionar sobre mi uso del teléfono en ese momento."),
                ),
            ),
            FormSection(
                "Agencia y control percibido",
                listOf(
                    choice("d6_1", "Sentí que tenía control sobre cuándo y cómo continuar mi actividad después de la interrupción."),
                ),
            ),
            FormSection(
                "Confianza / percepción de autoridad del sistema",
                listOf(
                    choice("d7_1", "Confié en el criterio de la aplicación para decidir cuándo mostrar una intervención visual"),
                ),
            ),
            FormSection(
                "Utilidad percibida y disposición a continuar",
                listOf(
                    choice(
                        "d8_1",
                        "En general, ¿qué tan útil te resultó la intervención?",
                        options("(1) Nada útil", "(2) Poco útil", "(3) Útil", "(4) Moderadamente útil", "(5) Muy útil"),
                    ),
                    choice("d8_2", "Como resultado de usar la aplicación, siento que cambió mi relación con el uso del teléfono."),
                    open("d8_3", "Describe brevemente, si lo hubo, ese cambio."),
                    choice("d8_4", "Continuaría usando esta aplicación si estuviera disponible después de concluido el estudio."),
                ),
            ),
            FormSection(
                "Anticipación y prevención",
                listOf(
                    choice("d9_1", "Las intervenciones visuales por revisiones frecuentes aparecieron a tiempo, antes de que empezara a revisar el teléfono una y otra vez."),
                    choice("d9_2", "Después de una intervención visual por revisiones frecuentes, revisé el teléfono menos veces durante el rato siguiente."),
                    choice("d9_3", "Las intervenciones visuales por uso prolongado sin pausa aparecieron a tiempo, antes de que ese periodo se extendiera más de lo que quería."),
                    choice("d9_4", "Después de una intervención visual por uso prolongado sin pausa, terminé o acorté ese periodo de uso."),
                ),
            ),
            FormSection(
                "Omisiones / falsos negativos",
                listOf(
                    choice("d10_1", "¿Con qué frecuencia notaste que revisabas el teléfono una y otra vez sin que apareciera una intervención visual?", HOW_OFTEN),
                    choice("d10_2", "¿Con qué frecuencia notaste que llevabas más tiempo del que querías en el teléfono sin que apareciera una intervención visual?", HOW_OFTEN),
                ),
            ),
            FormSection(
                "Interferencia técnica / supresión inesperada",
                listOf(
                    choice(
                        "d11_1",
                        "¿Notaste que la aplicación dejó de funcionar, se cerró o dejó de mostrar interrupciones de forma inesperada durante el periodo de estudio?",
                        listOf(Option(1, "Sí"), Option(0, "No")),
                    ),
                    open("d11_2", "Si respondiste Sí: describe brevemente cuándo ocurrió y qué hiciste al respecto."),
                ),
            ),
            FormSection(
                "Espacio abierto",
                listOf(
                    open("d12_1", "Si deseas dejar algún comentario, plantear inquietudes o formular preguntas, puedes hacerlo aquí."),
                ),
            ),
        ),
    )

    val END_FORMS: List<FormDefinition> = listOf(USABILITY)
}
