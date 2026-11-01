package com.samosaai.masala.widgets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** The widget store without a database: pure in-memory values (Room only persists them). */
class WidgetStateStoreTest {

    private fun store() = WidgetStateStore(CoroutineScope(Dispatchers.Unconfined), null)

    @Test
    fun counterStartsAtZeroAndSteps() {
        val s = store()
        assertEquals(0, s.int("w", "glasses"))
        s.increment("w", "glasses", 1)
        s.increment("w", "glasses", 2)
        assertEquals(3, s.int("w", "glasses"))
    }

    @Test
    fun checklistTicksRoundTrip() {
        val s = store()
        assertEquals(listOf(false, false), s.bools("w", "todo", 2))
        s.toggle("w", "todo", 0, 2)
        assertEquals(listOf(true, false), s.bools("w", "todo", 2))
        s.toggle("w", "todo", 0, 2)
        assertEquals(listOf(false, false), s.bools("w", "todo", 2))
    }

    @Test
    fun valuesArePerWidget() {
        val s = store()
        s.set("a", "k", "1")
        assertEquals(emptyMap<String, String>(), s.values("b"))
        assertEquals("1", s.values("a")["k"])
    }
}
