package com.example.smartuse_frontend.data

/** In-memory accumulator for the setup form. Never persisted. */
data class SetupDraft(
    val age: Int = 0,
    val gender: String = "",
    val universityMajor: String = "",
    val sasSvScore: Int = 0,
)
