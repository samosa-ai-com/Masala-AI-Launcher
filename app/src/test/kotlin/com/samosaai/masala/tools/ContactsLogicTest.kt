package com.samosaai.masala.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactsLogicTest {
    @Test fun `digits survive whatever separators the phone stored`() {
        assertEquals("919876543210", ContactsTool.digitsOf(" +91-98765 43210 "))
        assertEquals("98765", ContactsTool.digitsOf("98 765"))
        assertEquals("", ContactsTool.digitsOf("no digits here"))
    }

    @Test fun `the digit pattern needs three digits and bridges separators`() {
        assertEquals("%9%8%7%", ContactsTool.digitsPattern("987"))
        assertEquals(null, ContactsTool.digitsPattern("98"))
        assertEquals(null, ContactsTool.digitsPattern("98a"))
        // The wildcard form matches through any separators the phone stored.
        val like = ContactsTool.digitsPattern("9198765")!!
        assertTrue(like.startsWith("%9%1%") && like.endsWith("%5%"))
    }
}
