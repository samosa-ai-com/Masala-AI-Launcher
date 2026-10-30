package com.samosaai.masala

import com.samosaai.masala.core.layout.KnownSymbols
import com.samosaai.masala.ui.home.SymbolIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolIconsTest {
    @Test
    fun `every symbol a layout may name has an icon, and no icon is for an unknown name`() {
        assertEquals(KnownSymbols.names, SymbolIcons.names)
        assertTrue(KnownSymbols.names.all(SymbolIcons::has))
    }

    @Test
    fun `an unknown name still draws something`() {
        assertFalse(SymbolIcons.has("zzz"))
        assertEquals(SymbolIcons.Fallback.name, SymbolIcons.icon("zzz").name)
        assertTrue(SymbolIcons.icon("alarm").name.isNotEmpty())
    }

    @Test
    fun `layout tints are the Masala accents and an unknown tint follows the theme`() {
        assertEquals(0xFFEBCB80.toInt(), SymbolIcons.tint("yellow")!!.value.shr(32).toInt())
        assertEquals(null, SymbolIcons.tint("chartreuse"))
    }
}
