package com.samosaai.masala

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.skills.SkillRunEvent
import com.samosaai.masala.ui.skills.ResultCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A mini app a skill run made shows Open and Pin; one deleted since says so. */
@RunWith(AndroidJUnit4::class)
class ResultCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private var created: String? = null

    @After
    fun cleanUp() = runBlocking { created?.let { app.miniApps.delete(it) } ?: Unit }

    @Test
    fun miniAppCardOffersOpenAndPin() {
        val id = runBlocking { app.miniApps.create("Card test", "<html><body>hi</body></html>")!! }.also { created = it }
        compose.setContent { ResultCard(SkillRunEvent.Result("mini_app", "Card test", id)) {} }
        compose.onNodeWithText("Card test").assertIsDisplayed()
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Pin to home").assertIsDisplayed()
    }

    @Test
    fun deletedMiniAppSaysSo() {
        compose.setContent { ResultCard(SkillRunEvent.Result("mini_app", "Gone", "no-such-app")) {} }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("This mini app was deleted") > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
