package com.samosaai.masala.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPagesTest {
    @Test
    fun `what leaves the phone names the host, not the whole URL`() {
        assertEquals("api.meta.ai", hostOf("https://api.meta.ai/v1/"))
        assertEquals("192.168.0.129:1234", hostOf("http://192.168.0.129:1234/v1/"))
        assertEquals("example.org", hostOf("example.org"))
    }

    @Test
    fun `every bundled licence gets a readable title`() {
        assertEquals("Inter · SIL Open Font License 1.1", licenceTitle("OFL_inter.txt"))
        assertEquals("Space Grotesk · SIL Open Font License 1.1", licenceTitle("OFL_space_grotesk.txt"))
        assertEquals("Figtree · SIL Open Font License 1.1", licenceTitle("OFL_figtree.txt"))
        assertEquals("Apache LICENSE", licenceTitle("Apache_LICENSE.txt"))
    }
}
