package com.samosaai.masala.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniAppLoadReportTest {
    @Test fun errorsAreListedWithWhatToDo() {
        val text = MasalaTools.loadReport(listOf("ERROR: Uncaught ReferenceError: foo is not defined (x:3)"))
        assertTrue(text.contains("update_mini_app"))
        assertTrue(text.endsWith("foo is not defined (x:3)"))
    }

    @Test fun aCleanLoadAndAnUncheckedOneSaySo() {
        assertEquals("It loaded with no console errors.", MasalaTools.loadReport(emptyList()))
        assertEquals("It couldn't be checked for errors.", MasalaTools.loadReport(null))
    }
}
