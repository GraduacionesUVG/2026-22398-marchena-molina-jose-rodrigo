package com.example.smartuse_frontend.service

/**
 * What a reboot does: the service back in its mode (the cycle's schedule only
 * while the study runs), then the ending moved on from wherever it was.
 */
class BootStart(
    private val serviceMode: suspend () -> ServiceMode,
    private val startService: () -> Unit,
    private val ensureScheduled: () -> Unit,
    private val ensureEnding: suspend () -> Unit,
) {
    suspend fun run() {
        when (serviceMode()) {
            ServiceMode.OFF -> Unit
            ServiceMode.STUDY -> {
                startService()
                ensureScheduled()
            }
            ServiceMode.FORMS_REMINDER, ServiceMode.SENDING -> startService()
        }
        ensureEnding()
    }
}