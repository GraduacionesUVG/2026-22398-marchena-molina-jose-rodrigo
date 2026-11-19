package com.example.smartuse_frontend.e2e

import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Puts back the device state a clean install does not reset (airplane mode,
 * forced Doze, the app's standby bucket, the battery whitelist), even when the
 * test fails half-way, so no later test inherits it.
 */
class DeviceStateRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val power = context.getSystemService(PowerManager::class.java)
            val airplane = DeviceShell.airplaneModeOn()
            val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
            try {
                base.evaluate()
            } finally {
                DeviceShell.setAirplaneMode(airplane)
                DeviceShell.unforceIdle()
                DeviceShell.setStandbyBucket("active")
                if (exempt) DeviceShell.exemptFromBatteryOptimization() else DeviceShell.removeBatteryExemption()
            }
        }
    }
}
