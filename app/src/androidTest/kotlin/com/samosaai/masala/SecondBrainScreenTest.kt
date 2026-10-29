package com.samosaai.masala

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.ui.secondbrain.SecondBrainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the Second Brain screen: the list renders from the real file
 * vault, and the graph tab builds its layout and draws the canvas without
 * crashing. Guards the projection/canvas math and the snapshot wiring, which no
 * unit test can reach.
 *
 * The graph animates continuously (auto-rotation and neuron firings), so its
 * test drives the frame clock by hand: `waitForIdle` would wait for a frame that
 * never stops coming.
 *
 * Safe to run on the phone by hand, without uninstalling:
 *   adb shell am instrument -w -e class com.samosaai.masala.SecondBrainScreenTest \
 *     com.samosaai.masala.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SecondBrainScreenTest {
    @get:Rule val compose = createAndroidComposeRule<SecondBrainActivity>()

    private fun awaitList() = compose.waitUntil(15_000) {
        compose.onAllNodesWithText("Identity", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun listRendersTheVaultSections() {
        awaitList()
        compose.onNodeWithText("Areas", substring = true).assertExists()
        compose.onNodeWithText("People", substring = true).assertExists()
        compose.onNodeWithText("Notes", substring = true).assertExists()
        compose.onNodeWithText("Health", substring = true).assertExists()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun graphDrawsThenReturnsToTheList() {
        awaitList()
        // Manual clock: the graph never goes idle, so frames are pumped explicitly
        // while real time passes for the off-main layout pass.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Graph").performClick()
        repeat(25) {
            Thread.sleep(80)
            compose.mainClock.advanceTimeBy(16)
        }
        assertFalse("graph crashed the screen", compose.activity.isFinishing)
        compose.onNodeWithText("List").performClick()
        repeat(10) {
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(16)
        }
        assertFalse("returning to the list crashed the screen", compose.activity.isFinishing)
        assertTrue(
            "list did not come back",
            compose.onAllNodesWithText("Identity", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
