package com.samosaai.masala

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.launcher.AppCatalog
import com.samosaai.masala.ui.drawer.Drawer
import com.samosaai.masala.ui.drawer.Scrubber
import com.samosaai.masala.ui.drawer.SearchSources
import com.samosaai.masala.ui.drawer.SettingsEntry
import com.samosaai.masala.ui.theme.MasalaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI refresh U3: the A–Z scrubber and grouped search in the drawer. */
@RunWith(AndroidJUnit4::class)
class DrawerTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    private fun entry(label: String) = AppEntry(AppKey("test.$label", "A", 0), label, label, ProfileKind.PERSONAL, Visibility.DEFAULT, pinned = false)

    @Test
    fun scrubberWalksTheLettersInOrderUnderTheFinger() {
        val seen = mutableListOf<Char>()
        var ended = 0
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                Scrubber(letters = listOf('A', 'B', 'C', 'D', 'E'), onLetter = { seen += it }, onEnd = { ended++ })
            }
        }
        compose.onNodeWithContentDescription("A to Z: drag to jump to a letter").performTouchInput { swipeDown(startY = top + 1f, endY = bottom - 1f) }
        compose.waitForIdle()
        assertEquals('A', seen.first())
        assertEquals('E', seen.last())
        // Each letter once, in order: one tick per new letter.
        assertEquals(seen.distinct(), seen)
        assertEquals(seen.sorted(), seen)
        assertTrue(ended >= 1)
    }

    @Test
    fun searchGroupsAppsSkillsAndSettingsAndOffersAsk() {
        val sources = object : SearchSources {
            override fun skills(query: String) = listOf(MasalaSkill(id = "chef", title = "Chef", instructions = ""))
            override fun settings(query: String) = listOf(SettingsEntry("Voice", listOf("mic"), "VOICE"))
        }
        val apps = listOf(entry("Chess"), entry("Chrome"), entry("Camera"))
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                Drawer(
                    apps = apps, profiles = AppCatalog.Snapshot(), icons = app.icons, autoLaunch = false,
                    onLaunch = { _, _ -> }, onMenu = {}, onAsk = {}, onWorkPaused = {}, onOpenSettings = {},
                    sources = sources,
                )
            }
        }
        compose.onNodeWithContentDescription("Search apps, files or ask").performTextInput("ch")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("SKILLS")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("APPS")).assertIsDisplayed()
        compose.onNode(hasText("Chess")).assertIsDisplayed()
        compose.onNode(hasText("Chef")).assertIsDisplayed()
        compose.onNode(hasText("Voice")).assertIsDisplayed()
        compose.onNode(hasText("Ask Masala")).assertExists()
        // Camera doesn't match "ch".
        assertTrue(compose.onAllNodes(hasText("Camera")).fetchSemanticsNodes().isEmpty())
    }
}
