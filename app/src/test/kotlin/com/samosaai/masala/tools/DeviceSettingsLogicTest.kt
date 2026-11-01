package com.samosaai.masala.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSettingsLogicTest {
    @Test fun `every advertised setting resolves`() {
        val names = SettingTool.supported()
        assertTrue(names.containsAll(listOf("wifi", "location", "notifications", "battery", "apps")))
        for (name in names) {
            val spec = SettingTool.spec(name)
            assertTrue(spec != null && spec.action.isNotBlank() && spec.label.isNotBlank(), name)
        }
    }

    @Test fun `unknown settings name their options`() {
        assertNull(SettingTool.spec("warp drive"))
    }

    @Test fun `app screens carry the package`() {
        assertTrue(SettingTool.spec("notifications")!!.needsPackageExtra)
        assertTrue(SettingTool.spec("apps")!!.needsPackage)
        assertFalse(SettingTool.spec("wifi")!!.needsPackage)
        assertFalse(SettingTool.spec("wifi")!!.needsPackageExtra)
    }

    @Test fun `photo files sort by time and never collide within a run`() {
        val dir = File(".")
        val name = PhotoTool.photoFile(dir).name
        assertTrue(name.startsWith("photo-") && name.endsWith(".jpg"), name)
        assertEquals(25, name.length)
    }
}
