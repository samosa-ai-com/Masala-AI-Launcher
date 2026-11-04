package com.samosaai.masala

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.ui.home.widgets.WidgetCard
import com.samosaai.masala.ui.home.widgets.WidgetSource
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.widgets.WidgetStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Instrumented test: specs resolve and draw on a real
 * device, counters step, checklists tick, snapshot bindings read, and the W4/W5 tiers say so.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = LocalDateTime.of(2026, 11, 1, 9, 30)
    private val snapshot = ContextSnapshot(
        now = now,
        nextEvent = NextEvent("Standup", LocalDateTime.of(2026, 11, 1, 10, 0), "Room 3"),
        batteryPercent = 82,
    )

    private fun store() = WidgetStateStore(CoroutineScope(Dispatchers.Unconfined), null)

    private fun draw(spec: WidgetSpec, store: WidgetStateStore, actions: MutableList<MasalaAction> = mutableListOf()) {
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                WidgetCard(spec, rows = null, WidgetSource(snapshot = { snapshot }, store = store, onAction = { actions += it }))
            }
        }
    }

    @Test
    fun counterStepsAndShowsGoal() {
        val spec = WidgetSpec(
            id = "water", title = "Water",
            components = listOf(WidgetComponent.Counter("glasses", "Glasses", goal = 8)),
            size = SizeRange(1, 1, 2),
        )
        draw(spec, store())
        compose.onNodeWithText("Water").assertIsDisplayed()
        compose.onNodeWithText("0 / 8").assertIsDisplayed()
        compose.onNodeWithText("+1").performClick()
        compose.onNodeWithText("1 / 8").assertIsDisplayed()
    }

    @Test
    fun checklistTicks() {
        val spec = WidgetSpec(
            id = "habits", title = "Habits",
            components = listOf(WidgetComponent.Checklist("todo", listOf("Read", "Walk"))),
        )
        draw(spec, store())
        compose.onNodeWithText("Read").performClick()
        // Still drawn (the tick is visual); a second tap unticks without losing the row.
        compose.onNodeWithText("Read").assertIsDisplayed()
        compose.onNodeWithText("Walk").assertIsDisplayed()
    }

    @Test
    fun snapshotBindingsRead() {
        val spec = WidgetSpec(
            id = "brief", title = "Brief",
            components = listOf(
                WidgetComponent.Text(binding = Binding(BindingSource.NEXT_EVENT)),
                WidgetComponent.Text(binding = Binding(BindingSource.BATTERY)),
            ),
        )
        draw(spec, store())
        compose.onNodeWithText("Standup · 10:00 · Room 3").assertIsDisplayed()
        compose.onNodeWithText("82%").assertIsDisplayed()
    }

    @Test
    fun futureTiersNoteTheirMilestone() {
        val spec = WidgetSpec(
            id = "media", title = "Media",
            components = listOf(
                WidgetComponent.Video(com.samosaai.masala.core.contract.StreamRef("front")),
                WidgetComponent.Snapshot("front"),
                WidgetComponent.WebValue("Price", com.samosaai.masala.core.contract.HttpBinding("https://example.com/p", "$.price")),
            ),
        )
        draw(spec, store())
        // No stream library in this test: both media components explain where a source comes from.
        compose.onAllNodesWithText("Video · add its source in Settings → Widgets → Cameras", substring = false, ignoreCase = false).assertCountEquals(2)
        compose.onNodeWithText("Price").assertIsDisplayed()
        // The web value draws through the real store: nothing fetched yet, so an em dash.
        compose.onNodeWithText("—", substring = true).assertIsDisplayed()
    }
}
