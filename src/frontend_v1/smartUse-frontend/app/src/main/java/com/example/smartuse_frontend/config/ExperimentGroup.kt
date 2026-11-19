package com.example.smartuse_frontend.config

/**
 * The experiment arms the backend accepts. This app only ever registers
 * [CONTROL] (the data-collection group); the other values are kept for the
 * wire format.
 */
enum class ExperimentGroup(val wireValue: String) {
    CONTROL("control"),
    RATE("rate"),
    INFERENCE("inference"),
}
