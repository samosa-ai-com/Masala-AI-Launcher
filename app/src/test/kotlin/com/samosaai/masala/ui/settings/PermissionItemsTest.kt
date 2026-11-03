package com.samosaai.masala.ui.settings

import com.samosaai.masala.tools.Capability
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionItemsTest {
    private val items = allPermissionGroups().flatMap { it.items }

    @Test
    fun `every runtime permission on the page is declared in the manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val asked = items.flatMap { listOfNotNull(it.androidPermission) + it.extraPermissions }
        assertTrue(asked.isNotEmpty())
        asked.forEach { permission -> assertTrue("\"$permission\"" in manifest, "$permission is not in the manifest") }
    }

    @Test
    fun `every special access with a settings screen has exactly one row, and Termux has none`() {
        val shown = items.mapNotNull { (it.special as? Special.Of)?.capability }
        val expected = Capability.entries.filter { it.marker != null && it != Capability.HEALTH_CONNECT }
        assertEquals(expected.toSet(), shown.toSet())
        assertEquals(shown.size, shown.toSet().size)
        assertTrue(Capability.TERMUX !in shown)
        assertTrue(items.none { it.androidPermission?.startsWith("com.termux") == true })
    }

    @Test
    fun `the default home app and Health Connect have their rows`() {
        assertEquals(1, items.count { it.special == Special.DefaultHome })
        assertEquals(1, items.count { it.special == Special.HealthConnect })
    }
}
