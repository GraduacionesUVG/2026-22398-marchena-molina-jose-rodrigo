package com.example.smartuse_frontend.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartSettingsTest {

    @Test
    fun `listed brands need the step, whatever the case`() {
        listOf(
            "Xiaomi", "Redmi", "POCO", "HUAWEI", "HONOR", "OPPO", "realme", "OnePlus",
            "vivo", "iQOO", "samsung", "asus", "TECNO", "INFINIX", "itel",
        ).forEach { assertTrue(it, AutostartSettings.needsAutostartStep(it)) }
    }

    @Test
    fun `stock Android brands skip the step`() {
        listOf("Google", "motorola", "Nokia", "Sony", "unknown", "").forEach {
            assertFalse(it, AutostartSettings.needsAutostartStep(it))
        }
    }

    @Test
    fun `every brand's list ends with the app's own settings page`() {
        listOf("Xiaomi", "HUAWEI", "HONOR", "OPPO", "OnePlus", "vivo", "samsung", "asus", "TECNO", "Google").forEach {
            assertEquals(it, AutostartTarget.AppDetails, AutostartSettings.targets(it).last())
        }
    }

    @Test
    fun `sub-brands share their parent's screens`() {
        assertEquals(AutostartSettings.targets("Xiaomi"), AutostartSettings.targets("Redmi"))
        assertEquals(AutostartSettings.targets("Xiaomi"), AutostartSettings.targets("POCO"))
        assertEquals(AutostartSettings.targets("OPPO"), AutostartSettings.targets("realme"))
        assertEquals(AutostartSettings.targets("vivo"), AutostartSettings.targets("iQOO"))
        assertEquals(AutostartSettings.targets("TECNO"), AutostartSettings.targets("itel"))
    }

    @Test
    fun `a brand's own screens come first, in order`() {
        assertEquals(
            listOf(
                AutostartTarget.Screen(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                AutostartTarget.AppDetails,
            ),
            AutostartSettings.targets("Xiaomi"),
        )
        assertEquals(
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            (AutostartSettings.targets("OPPO").first() as AutostartTarget.Screen).className,
        )
    }

    @Test
    fun `a brand with no known screens only gets the app settings page`() {
        assertEquals(listOf(AutostartTarget.AppDetails), AutostartSettings.targets("Google"))
    }
}
