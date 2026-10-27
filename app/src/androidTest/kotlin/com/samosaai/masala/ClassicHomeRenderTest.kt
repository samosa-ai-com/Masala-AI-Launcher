package com.samosaai.masala

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.layout.LayoutResolver
import com.samosaai.masala.ui.home.LayoutRenderer
import com.samosaai.masala.ui.home.RenderContext
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.data.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Plan Phase 2's one instrumented test: `classic.json`, the shipped example, resolved and drawn by the
 * renderer. Checks the clock, that items read out their labels, the dock, and that a tap reaches the
 * dispatcher as the item's action.
 */
@RunWith(AndroidJUnit4::class)
class ClassicHomeRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    private fun classic(): LayoutDoc {
        val json = checkNotNull(javaClass.classLoader?.getResourceAsStream("masala/examples/classic.json")) { "classic.json isn't packaged" }
            .bufferedReader().use { it.readText() }
        return MasalaJson.strict.decodeFromString(json)
    }

    @Test
    fun classicHomeShowsClockItemsAndDock() {
        val now = LocalDateTime.parse("2026-10-27T07:30")
        val resolved = LayoutResolver.resolve(classic(), emptyList(), ContextSnapshot(now = now))
        val actions = mutableListOf<MasalaAction>()

        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                LayoutRenderer(
                    resolved,
                    RenderContext(app.icons, emptyList(), now, locked = false, onAction = { actions += it }, onAppMenu = {}, onClockClick = {}),
                )
            }
        }

        // The header's clock, in the phone's format.
        compose.onNode(hasContentDescription("7:30", substring = true)).assertIsDisplayed()
        // Dock items read out their label and the drawer item opens the drawer.
        compose.onNodeWithContentDescription("Phone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chrome").assertIsDisplayed()
        compose.onNodeWithContentDescription("All apps").performClick()
        assertEquals(listOf<MasalaAction>(MasalaAction.OpenDrawer), actions)
    }
}
