package com.samosaai.masala

import com.samosaai.masala.core.layout.KnownSymbols
import com.samosaai.masala.ui.home.SymbolIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolIconsTest {
    @Test
    fun `every symbol a layout may name has an icon, and no icon is for an unknown name`() {
        assertEquals(KnownSymbols.names, SymbolIcons.names)
    }

    @Test
    fun `an unknown name still draws something`() {
        assertEquals("Z", SymbolIcons.glyph("zzz"))
        assertTrue(SymbolIcons.glyph("alarm").isNotEmpty())
    }
}
