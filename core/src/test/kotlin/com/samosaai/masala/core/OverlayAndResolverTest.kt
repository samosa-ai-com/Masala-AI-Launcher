package com.samosaai.masala.core

import com.samosaai.masala.core.Fixtures.at
import com.samosaai.masala.core.contract.AddItem
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutResolver
import com.samosaai.masala.core.layout.OverlayMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayAndResolverTest {
    private val workday = Fixtures.layout("workday")
    private val focus = Fixtures.mode("mode-focus")
    private val night = Fixtures.mode("mode-quiet-nights")

    private fun LayoutDoc.itemIds(sectionId: String) =
        pages.flatMap { it.sections }.filterIsInstance<ItemSection>().first { it.id == sectionId }.items.map { it.id }

    private fun LayoutDoc.sectionIds(pageId: String) = pages.first { it.id == pageId }.sections.map { it.id }

    @Test
    fun `focus hides and adds items`() {
        val result = OverlayMerge.merge(workday, listOf(focus))
        assertEquals(emptyList(), result.conflicts)
        assertEquals(listOf("gym", "focus-timer"), result.layout.itemIds("routine"))
    }

    @Test
    fun `quiet nights adds a section after the clock and hides others`() {
        val result = OverlayMerge.merge(workday, listOf(night))
        assertEquals(emptyList(), result.conflicts)
        assertEquals(listOf("clock", "night", "next-event-card", "dock"), result.layout.sectionIds("today"))
    }

    @Test
    fun `patches apply in a fixed order whatever order they come in`() {
        assertEquals(
            OverlayMerge.merge(workday, listOf(focus, night)),
            OverlayMerge.merge(workday, listOf(night, focus)),
        )
    }

    @Test
    fun `a later patch can hide what an earlier one added`() {
        val hideTimer = ModePatch(id = "z", name = "Z", order = 99, hideItems = listOf("focus-timer"))
        val result = OverlayMerge.merge(workday, listOf(hideTimer, focus))
        assertEquals(listOf("gym"), result.layout.itemIds("routine"))
    }

    @Test
    fun `bad adds are reported and skipped, not thrown`() {
        val gym = workday.pages[0].sections.filterIsInstance<ItemSection>().first { it.id == "routine" }.items[0]
        val bad = ModePatch(
            id = "bad",
            name = "Bad",
            addItems = listOf(
                AddItem("routine", gym), // duplicate ID
                AddItem("clock", gym.copy(id = "new")), // header can't hold items
                AddItem("missing", gym.copy(id = "new2")),
            ),
        )
        val result = OverlayMerge.merge(workday, listOf(bad))
        assertEquals(3, result.conflicts.size)
        assertEquals(workday, result.layout)
    }

    @Test
    fun `resolver applies rules for the time of day`() {
        // Gym shows 06:00-09:00 on Mon/Wed/Fri, dinner 16:00-21:00; the event card needs an event.
        val tuesdayMorning = LayoutResolver.resolve(workday, emptyList(), at("07:30")).layout
        assertEquals(listOf("playlist"), tuesdayMorning.itemIds("routine"))
        assertEquals(listOf("clock", "focus-stack", "routine", "dock"), tuesdayMorning.sectionIds("today"))

        val mondayMorning = LayoutResolver.resolve(workday, emptyList(), at("07:30", day = 26)).layout
        assertEquals(listOf("gym", "playlist"), mondayMorning.itemIds("routine"))

        val evening = LayoutResolver.resolve(workday, emptyList(), at("18:00")).layout
        assertEquals(listOf("playlist", "dinner"), evening.itemIds("routine"))
    }

    @Test
    fun `event card appears and grows as the event gets close`() {
        val soon = at("09:00").copy(nextEvent = NextEvent("Standup", at("09:40").now))
        val r1 = LayoutResolver.resolve(workday, emptyList(), soon)
        assertTrue("next-event-card" in r1.layout.sectionIds("today"))
        assertEquals(1, r1.widgetRows["next-event-card"])

        val imminent = soon.copy(now = at("09:30").now)
        assertEquals(2, LayoutResolver.resolve(workday, emptyList(), imminent).widgetRows["next-event-card"])
    }

    @Test
    fun `two placements of one widget keep their own heights`() {
        // Review fix: rows used to be keyed by widget id, so two placements shared one height.
        val lib = mapOf(
            "w" to WidgetSpec(
                "w", "W",
                listOf(WidgetComponent.Counter("n", "N")),
                SizeRange(1, 1, 2),
            ),
        )
        val doc = LayoutDoc(
            pages = listOf(
                Page(
                    "home",
                    sections = listOf(
                        WidgetSection("a", ref = WidgetRef("w")),
                        WidgetSection("b", ref = WidgetRef("w", rowsOverride = 2)),
                    ),
                ),
            ),
        )
        val rows = LayoutResolver.resolve(doc, emptyList(), at("12:00"), lib).widgetRows
        assertEquals(1, rows["a"])
        assertEquals(2, rows["b"])
    }

    @Test
    fun `stack is ordered by priority in the evening`() {
        fun stackOrder(time: String) = LayoutResolver.resolve(workday, emptyList(), at(time)).layout
            .pages[0].sections.filterIsInstance<StackSection>().single().widgets.map { it.id }
        assertEquals(listOf("water", "steps"), stackOrder("10:00"))
        assertEquals(listOf("steps", "water"), stackOrder("18:00"))
    }

    @Test
    fun `modes and rules together`() {
        val ctx = at("23:00").copy(activeModes = listOf("quiet-nights"))
        val r = LayoutResolver.resolve(workday, listOf(night), ctx)
        assertEquals(listOf("clock", "night", "dock"), r.layout.sectionIds("today"))
        assertEquals(listOf("alarm"), r.layout.itemIds("night")) // no urgent notifications
        assertTrue(r.layout.pages[0].sections[0] is HeaderSection)
    }
}
