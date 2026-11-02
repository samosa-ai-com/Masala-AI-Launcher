package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The already-exists refusal in create_widget keys on titles only (plan §2). */
class WidgetDedupeTest {
    private fun spec(id: String, title: String?) = WidgetSpec(
        id = id,
        title = title,
        components = listOf(WidgetComponent.Counter("n", "N")),
    )

    @Test fun `exact title matches`() {
        val lib = mapOf("water" to spec("water", "Water"))
        assertEquals("water", WidgetTools.findSimilarWidget(lib, spec("water-2", "Water"))?.id)
    }

    @Test fun `normalized titles match`() {
        val lib = mapOf("pomodoro" to spec("pomodoro", "Pomodoro and Work"))
        assertEquals("pomodoro", WidgetTools.findSimilarWidget(lib, spec("x", "  pomodoro-AND work!! "))?.id)
    }

    @Test fun `different titles do not match even with the same key and id stem`() {
        val lib = mapOf("cycle" to spec("cycle", "Cycle"))
        assertNull(WidgetTools.findSimilarWidget(lib, spec("cycle", "Habit loop")))
        assertNull(WidgetTools.findSimilarWidget(lib, spec("other", "Other")))
    }

    @Test fun `untitled specs never match`() {
        val lib = mapOf("cycle" to spec("cycle", "Cycle"))
        assertNull(WidgetTools.findSimilarWidget(lib, spec("cycle", null)))
    }

    @Test fun `blank titles never match`() {
        val lib = mapOf("cycle" to spec("cycle", "Cycle"))
        assertNull(WidgetTools.findSimilarWidget(lib, spec("x", "  ")))
    }
}
