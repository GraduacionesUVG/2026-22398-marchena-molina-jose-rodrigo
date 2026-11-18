package com.example.smartuse_frontend.config

/**
 * The researcher-facing experiment-group selector, disguised as an innocuous
 * metadata field so a participant observing setup cannot infer their arm.
 * Not shown in this app: the profile step always picks [ExperimentGroup.CONTROL].
 *
 * The selected option is never sent to the backend and never persisted — it is
 * translated to an [ExperimentGroup] in memory at form submit.
 */
object ExperimentGroupConfig {
    const val FIELD_LABEL = "Mes de verificación"

    /** Display order. */
    val options: List<Pair<String, ExperimentGroup>> = listOf(
        "Julio" to ExperimentGroup.CONTROL,
        "Agosto" to ExperimentGroup.RATE,
        "Septiembre" to ExperimentGroup.INFERENCE,
    )

    fun resolve(optionLabel: String): ExperimentGroup? =
        options.firstOrNull { it.first == optionLabel }?.second
}
