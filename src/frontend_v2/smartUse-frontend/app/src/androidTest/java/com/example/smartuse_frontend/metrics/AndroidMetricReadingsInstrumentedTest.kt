package com.example.smartuse_frontend.metrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMetricReadingsInstrumentedTest {
    private val readings = AndroidMetricReadings(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun readsABatteryPercentage() {
        val pct = readings.batteryPct()
        assertNotNull(pct)
        assertTrue("battery $pct", pct!! in 0..100)
    }

    @Test
    fun readsAPositiveResidentMemory() {
        val rss = readings.rssKb()
        assertNotNull(rss)
        assertTrue("rss $rss", rss!! > 0)
    }
}
