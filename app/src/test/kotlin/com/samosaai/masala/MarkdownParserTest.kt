package com.samosaai.masala

import com.samosaai.masala.ui.components.MarkdownParser
import com.samosaai.masala.ui.components.MarkdownSegment
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownParserTest {
    @Test fun fencedBlocksAreSplitOut() {
        val md = "Run this:\n\n```sh\nls -la\n```\n\nThen done."
        assertEquals(
            listOf(MarkdownSegment.Prose("Run this:"), MarkdownSegment.Code("ls -la", "sh"), MarkdownSegment.Prose("Then done.")),
            MarkdownParser.segments(md),
        )
    }

    @Test fun unclosedFenceRunsToTheEnd() {
        assertEquals(listOf(MarkdownSegment.Code("a\nb", null)), MarkdownParser.segments("```\na\nb"))
    }

    @Test fun longerFenceIsNotClosedByShorterOne() {
        assertEquals(listOf(MarkdownSegment.Code("```\ninner\n```", null)), MarkdownParser.segments("````\n```\ninner\n```\n````"))
    }
}
