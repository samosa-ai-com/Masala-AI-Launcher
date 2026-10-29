package com.samosaai.masala.core

import com.samosaai.masala.core.files.IndexDiff
import com.samosaai.masala.core.files.IndexRules
import com.samosaai.masala.core.files.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexRulesTest {
    @Test
    fun `app data, media and hidden folders are skipped`() {
        listOf("Android", "DCIM", "Pictures", "Movies", "Music", "Recordings").forEach {
            assertFalse(IndexRules.walksInto(it, depth = 1), it)
        }
        assertFalse(IndexRules.walksInto(".thumbnails", depth = 3))
        assertTrue(IndexRules.walksInto("Documents", depth = 1))
        assertTrue(IndexRules.walksInto("Download", depth = 1))
    }

    @Test
    fun `a media folder name deeper down is still searched`() {
        assertTrue(IndexRules.walksInto("Music", depth = 2))
        assertTrue(IndexRules.walksInto("Android", depth = 3))
    }

    @Test
    fun `every visible file is indexed by name, text only from documents within the size limit`() {
        assertTrue(IndexRules.indexes("holiday.jpg"))
        assertFalse(IndexRules.indexes(".nomedia"))
        assertTrue(IndexRules.readsContent("Lease.PDF", 200_000))
        assertTrue(IndexRules.readsContent("notes.md", 10))
        assertFalse(IndexRules.readsContent("holiday.jpg", 200_000))
        assertFalse(IndexRules.readsContent("empty.txt", 0))
        assertFalse(IndexRules.readsContent("huge.pdf", IndexRules.MAX_CONTENT_BYTES + 1))
    }

    @Test
    fun `stored text is collapsed and capped`() {
        assertEquals("a b c", IndexRules.storedText("  a\n\n b\t c  "))
        assertEquals(IndexRules.MAX_TEXT_CHARS, IndexRules.storedText("x".repeat(100_000)).length)
    }
}

class IndexDiffTest {
    @Test
    fun `new, changed, gone and unchanged files are told apart`() {
        val diff = IndexDiff.of(
            onDisk = mapOf("/a" to 1L, "/b" to 5L, "/new" to 9L),
            stored = mapOf("/a" to 1L, "/b" to 2L, "/old" to 3L),
        )
        assertEquals(listOf("/new"), diff.added)
        assertEquals(listOf("/b"), diff.changed)
        assertEquals(listOf("/old"), diff.gone)
        assertEquals(1, diff.unchanged)
        assertEquals(listOf("/new", "/b"), diff.toRead)
    }

    @Test
    fun `a first run reads everything and a repeat run reads nothing`() {
        val disk = mapOf("/a" to 1L, "/b" to 2L)
        assertEquals(listOf("/a", "/b"), IndexDiff.of(disk, emptyMap()).toRead)
        val again = IndexDiff.of(disk, disk)
        assertTrue(again.toRead.isEmpty() && again.gone.isEmpty())
        assertEquals(2, again.unchanged)
    }
}

class SearchQueryTest {
    @Test
    fun `words are quoted and the last is a prefix`() {
        assertEquals("\"tax\" \"retu*\"", SearchQuery.match("Tax retu"))
    }

    @Test
    fun `query syntax in the text is only words`() {
        assertEquals("\"deposit\" \"and\" \"not\" \"lease*\"", SearchQuery.match("deposit AND NOT \"lease\""))
        assertEquals("\"rent*\"", SearchQuery.match("-rent*"))
    }

    @Test
    fun `no words, no query`() {
        assertNull(SearchQuery.match("  ?! * "))
    }

    @Test
    fun `letters beyond ascii and digits are words`() {
        assertEquals("\"mietvertrag\" \"2025*\"", SearchQuery.match("Mietvertrag 2025"))
        assertEquals("\"किराया*\"", SearchQuery.match("किराया"))
    }
}
