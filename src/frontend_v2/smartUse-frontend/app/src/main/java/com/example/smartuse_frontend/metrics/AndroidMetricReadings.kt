package com.example.smartuse_frontend.metrics

import android.content.Context
import android.os.BatteryManager
import java.io.File

/** The real readings: BatteryManager's capacity and CURRENT_NOW (raw, the device's unit and sign) and VmRSS. */
class AndroidMetricReadings(context: Context) : MetricReadings {
    private val battery: BatteryManager? = context.applicationContext.getSystemService(BatteryManager::class.java)

    override fun batteryPct(): Int? = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    override fun currentNow(): Int? = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

    override fun rssKb(): Long? = parseVmRssKb(File("/proc/self/status").readText())
}
