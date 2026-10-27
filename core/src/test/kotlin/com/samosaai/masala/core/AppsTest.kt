package com.samosaai.masala.core

import com.samosaai.masala.core.apps.AppCatalogMerge
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.AppOverride
import com.samosaai.masala.core.apps.AppSearch
import com.samosaai.masala.core.apps.InstalledActivity
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.MasalaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppsTest {

    private val gmailPersonal = AppKey("com.google.android.gm", "com.google.android.gm.ConversationListActivityGmail", 0)
    private val gmailWork = gmailPersonal.copy(user = 10)
    private val maps = AppKey("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", 0)

    private val installed = listOf(
        InstalledActivity(gmailWork, "Gmail", ProfileKind.WORK),
        InstalledActivity(maps, "Maps", ProfileKind.PERSONAL),
        InstalledActivity(gmailPersonal, "Gmail", ProfileKind.PERSONAL),
    )

    @Test
    fun `the same app in two profiles is two entries, and an override applies to one`() {
        val overrides = listOf(
            AppOverride(gmailWork, label = "Work mail", visibility = Visibility.SEARCH_ONLY),
            AppOverride(maps, pinned = true),
        ).associateBy { it.key }

        val entries = AppCatalogMerge.merge(installed, overrides)

        assertEquals(listOf(gmailPersonal, maps, gmailWork), entries.map { it.key })
        val (personal, _, work) = entries
        assertEquals("Gmail", personal.label)
        assertEquals(Visibility.DEFAULT, personal.visibility)
        assertEquals("Work mail", work.label)
        assertEquals("Gmail", work.originalLabel)
        assertEquals(Visibility.SEARCH_ONLY, work.visibility)
        assertEquals(true, entries[1].pinned)
    }

    @Test
    fun `a blank rename falls back to the app's label`() {
        val entries = AppCatalogMerge.merge(installed, mapOf(maps to AppOverride(maps, label = "  ")))
        assertEquals("Maps", entries.single { it.key == maps }.label)
    }

    @Test
    fun `keys round-trip and match the contracts`() {
        assertEquals("com.google.android.gm/com.google.android.gm.ConversationListActivityGmail#10", gmailWork.id)
        assertEquals(gmailWork, AppKey.parse(gmailWork.id))
        assertEquals(MasalaAction.LaunchApp(gmailWork.packageName, gmailWork.activity, 10), gmailWork.action)
        assertEquals(IconRef.AppIcon(gmailWork.packageName, gmailWork.activity, 10), gmailWork.icon)
        assertFailsWith<IllegalArgumentException> { AppKey.parse("com.example") }
    }

    @Test
    fun `normalization drops case, accents and separators`() {
        assertEquals("cafe au lait", AppSearch.normalize("  Café-au_lait "))
        assertEquals("微信", AppSearch.normalize("微信"))
    }

    @Test
    fun `prefix beats word start beats substring beats fuzzy`() {
        assertEquals(AppSearch.PREFIX, AppSearch.match("goo", "Google Maps")?.tier)
        assertEquals(AppSearch.WORD_PREFIX, AppSearch.match("ma", "Google Maps")?.tier)
        assertEquals(AppSearch.SUBSTRING, AppSearch.match("ogl", "Google")?.tier)
        assertEquals(AppSearch.FUZZY, AppSearch.match("gm", "Google Maps")?.tier)
        assertEquals(AppSearch.FUZZY, AppSearch.match("yt", "YouTube")?.tier)
        assertEquals(AppSearch.PREFIX, AppSearch.match("cafe", "Café")?.tier)
        assertNull(AppSearch.match("ae", "Calendar"))
        assertNull(AppSearch.match("zz", "Google"))
        assertNull(AppSearch.match("  ", "Google"))
    }

    @Test
    fun `rank orders by match quality, then keeps catalog order`() {
        val labels = listOf("Camera", "Calendar", "Calculator", "Pocket Casts", "Clock")
        val ranked = AppSearch.rank("ca", labels) { listOf(it) }
        assertEquals(listOf("Camera", "Calendar", "Calculator", "Pocket Casts"), ranked)

        // Word starts beat letters scattered through the label.
        assertEquals(listOf("Google Maps", "Game Hub"), AppSearch.rank("gm", listOf("Game Hub", "Google Maps")) { listOf(it) })
    }

    @Test
    fun `a renamed app is found by either name`() {
        val entry = AppEntry(gmailWork, "Work mail", "Gmail", ProfileKind.WORK, Visibility.DEFAULT, pinned = false)
        val labels = { e: AppEntry -> listOf(e.label, e.originalLabel) }
        assertEquals(listOf(entry), AppSearch.rank("work", listOf(entry), labels))
        assertEquals(listOf(entry), AppSearch.rank("gmail", listOf(entry), labels))
    }

    @Test
    fun `auto-launch waits for composing text and respects a leading space`() {
        assertEquals("Maps", AppSearch.autoLaunchTarget("map", composing = false, results = listOf("Maps")))
        assertNull(AppSearch.autoLaunchTarget("微", composing = true, results = listOf("微信")))
        assertNull(AppSearch.autoLaunchTarget(" map", composing = false, results = listOf("Maps")))
        assertNull(AppSearch.autoLaunchTarget("ma", composing = false, results = listOf("Maps", "Mail")))
    }
}
