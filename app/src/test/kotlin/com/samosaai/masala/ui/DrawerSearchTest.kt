package com.samosaai.masala.ui

import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.launcher.HomeSearchSources
import com.samosaai.masala.ui.drawer.SearchGroups
import com.samosaai.masala.ui.drawer.SettingsEntry
import com.samosaai.masala.ui.drawer.sectionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawerSearchTest {
    private fun app(label: String) = AppEntry(AppKey("p.$label", "A", 0), label, label, ProfileKind.PERSONAL, Visibility.DEFAULT, pinned = false)

    @Test
    fun `ask comes first when nothing matches well, after the apps when one does`() {
        assertTrue(SearchGroups.build("deposit", emptyList()).askFirst)
        // A fuzzy hit ("dp" in "Deep") isn't a good match.
        assertTrue(SearchGroups.build("dpst", listOf(app("Deep Sleep Tracker"))).askFirst)
        assertFalse(SearchGroups.build("cam", listOf(app("Camera"))).askFirst)
        assertFalse(SearchGroups.build("maps", listOf(app("Google Maps"))).askFirst)
        val skill = MasalaSkill(id = "chef", title = "Chef", instructions = "")
        assertFalse(SearchGroups.build("chef", emptyList(), skills = listOf(skill)).askFirst)
    }

    @Test
    fun `groups are capped and the suggestions come from the query`() {
        val g = SearchGroups.build("  rice ", (1..20).map { app("Rice $it") }) { listOf("about $it", "remind $it") }
        assertEquals(12, g.apps.size)
        assertEquals(listOf("about rice", "remind rice"), g.suggestions)
    }

    @Test
    fun `settings match on a title or a word start, not on one letter`() {
        val all = listOf(SettingsEntry("Models & keys", listOf("provider", "api key"), "AI_MODELS"), SettingsEntry("Voice", listOf("mic"), "VOICE"))
        assertEquals(listOf("AI_MODELS"), SearchGroups.matchSettings("key", all).map { it.page })
        assertEquals(listOf("AI_MODELS"), SearchGroups.matchSettings("prov", all).map { it.page })
        assertEquals(listOf("VOICE"), SearchGroups.matchSettings("mic", all).map { it.page })
        assertTrue(SearchGroups.matchSettings("m", all).isEmpty())
    }

    @Test
    fun `file hits are read from the file_search answer`() {
        val text = """
            1. /storage/emulated/0/Documents/Flat/rental-agreement.pdf (modified 2026-10-20)
               …a security deposit of 1500 euros…
            2. /sdcard/Documents/Flat/flat-checklist.docx (modified 2026-10-21)
        """.trimIndent()
        val hits = HomeSearchSources.parseFileHits(text)
        assertEquals(2, hits.size)
        assertEquals("/storage/emulated/0/Documents/Flat/rental-agreement.pdf", hits[0].path)
        assertEquals("Documents/Flat", hits[0].where)
        assertEquals("…a security deposit of 1500 euros…", hits[0].passage)
        assertEquals("", hits[1].passage)
        assertTrue(HomeSearchSources.parseFileHits("No indexed file matches \"x\".").isEmpty())
    }

    @Test
    fun `apps file under their first letter, accents folded, others under hash`() {
        assertEquals('C', sectionOf("camera"))
        assertEquals('E', sectionOf("Éclair"))
        assertEquals('#', sectionOf("1Password"))
        assertEquals('#', sectionOf("微信"))
    }
}
