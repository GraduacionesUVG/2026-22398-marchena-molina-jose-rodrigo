package com.example.smartuse_frontend.ui.setup

/** The SAS-SV total: 10 items scored 1–6. */
const val SAS_SV_MIN_SCORE = 10
const val SAS_SV_MAX_SCORE = 60

/** The screening score typed at setup, or null unless it is a whole number in 10–60. */
fun parseSasSvScore(text: String): Int? =
    text.trim()
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { it in SAS_SV_MIN_SCORE..SAS_SV_MAX_SCORE }