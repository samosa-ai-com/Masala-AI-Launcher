package com.samosaai.masala

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import com.samosaai.masala.data.DrawerLayout
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
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
import com.samosaai.masala.ui.drawer.blockTouches
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

    /** Touches on the drawer's empty space never reach home underneath (home blocks itself while it's open). */
    @Test
    fun emptyDrawerSpaceDoesNotReachHomeUnderneath() {
        var homeTaps = 0
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().semantics { testTag = "home" }.blockTouches(true).clickable { homeTaps++ },
                    )
                    Drawer(
                        apps = listOf(entry("Camera")), profiles = AppCatalog.Snapshot(), icons = app.icons, autoLaunch = false,
                        onLaunch = { _, _ -> }, onMenu = {}, onAsk = {}, onWorkPaused = {}, onOpenSettings = {},
                    )
                }
            }
        }
        // Near the bottom of the screen: no app or button there, only the sheet.
        compose.onNodeWithTag("home").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, bottom - 40f)) }
        compose.waitForIdle()
        assertEquals(0, homeTaps)
    }

    /** A tap whose finger moves a few pixels still switches the layout (the sheet must not swallow it). */
    @Test
    fun aWobblyTapOnALayoutButtonStillSwitches() {
        val picked = mutableListOf<DrawerLayout>()
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                Drawer(
                    apps = listOf(entry("Camera")), profiles = AppCatalog.Snapshot(), icons = app.icons, autoLaunch = false,
                    onLaunch = { _, _ -> }, onMenu = {}, onAsk = {}, onWorkPaused = {}, onOpenSettings = {},
                    onLayout = { picked += it },
                )
            }
        }
        compose.onNodeWithContentDescription("Globe layout").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(3f, 2f))
            moveBy(androidx.compose.ui.geometry.Offset(-2f, 1f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(DrawerLayout.GLOBE), picked)
    }
}
