package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.config.ExperimentGroup
import com.example.smartuse_frontend.config.ExperimentGroupConfig

/** In-memory accumulator for the setup form. Never persisted. */
data class SetupDraft(
    val age: Int = 0,
    val gender: String = "",
    val universityMajor: String = "",
    val sasSvScore: Int = 0,
    val monthOption: String = "",
) {
    val resolvedGroup: ExperimentGroup?
        get() = ExperimentGroupConfig.resolve(monthOption)
}
