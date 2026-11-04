package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class LayoutLossTest {
    private fun app(id: String) = Item(id, id, IconRef.Symbol("apps"), MasalaAction.OpenMiniApp(id))
    private fun doc(vararg sections: com.samosaai.masala.core.contract.Section) =
        LayoutDoc(pages = listOf(Page("p1", sections = sections.toList())))

    private val home = doc(
        GridSection("fav", items = listOf(app("a"), app("b"), app("c"))),
        WidgetSection("w1", ref = WidgetRef("weather")),
    )

    @Test
    fun aProbeLayoutThatKeepsAlmostNothingDropsMost() {
        // Seen on the phone: a "probe" layout with one widget replaced the whole home.
        val probe = doc(WidgetSection("w1", ref = WidgetRef("weather")))
        assertEquals(1 to 4, LayoutLoss.kept(home, probe))
        assertTrue(LayoutLoss.dropsMost(home, probe))
    }

    @Test
    fun rearrangingWithNewIdsAndAddingIsNotALoss() {
        val moved = doc(
            WidgetSection("new-w", ref = WidgetRef("weather")),
            WidgetSection("cam", ref = WidgetRef("camera_live")),
            GridSection("other", items = listOf(app("c").copy(id = "x1"), app("a").copy(id = "x2"), app("b"))),
        )
        assertEquals(4 to 4, LayoutLoss.kept(home, moved))
        assertFalse(LayoutLoss.dropsMost(home, moved))
    }

    @Test
    fun removingOneThingIsAllowed() {
        val fewer = doc(GridSection("fav", items = listOf(app("a"), app("b"))), WidgetSection("w1", ref = WidgetRef("weather")))
        assertFalse(LayoutLoss.dropsMost(home, fewer))
    }

    @Test
    fun aNearlyEmptyHomeIsNeverGuarded() {
        val tiny = doc(GridSection("fav", items = listOf(app("a"))))
        assertFalse(LayoutLoss.dropsMost(tiny, doc(WidgetSection("w", ref = WidgetRef("weather")))))
    }
}
