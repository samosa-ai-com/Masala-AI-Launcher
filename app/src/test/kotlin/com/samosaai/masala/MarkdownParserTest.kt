package com.samosaai.masala

import com.samosaai.masala.ui.components.MarkdownParser
import com.samosaai.masala.ui.components.MarkdownSegment
import com.samosaai.masala.ui.components.MdBlock
import com.samosaai.masala.ui.components.MdSpan
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

    @Test fun blocks() {
        val blocks = MarkdownParser.blocks("# Title\n\nSome text\nover two lines\n\n- one\n  - nested\n1. first\n> quoted")
        assertEquals(
            listOf(
                MdBlock.Heading(1, listOf(MdSpan("Title"))),
                MdBlock.Paragraph(listOf(MdSpan("Some text over two lines"))),
                MdBlock.Bullet(0, listOf(MdSpan("one"))),
                MdBlock.Bullet(1, listOf(MdSpan("nested"))),
                MdBlock.Numbered("1", listOf(MdSpan("first"))),
                MdBlock.Quote(listOf(MdSpan("quoted"))),
            ),
            blocks,
        )
    }

    @Test fun inlineSpans() {
        assertEquals(
            listOf(MdSpan("a "), MdSpan("bold", bold = true), MdSpan(", "), MdSpan("it", italic = true), MdSpan(" and "), MdSpan("code", code = true), MdSpan(" see "), MdSpan("docs")),
            MarkdownParser.spans("a **bold**, *it* and `code` see [docs](https://x.y)"),
        )
    }

    @Test fun snakeCaseWordsAreNotItalic() {
        assertEquals(listOf(MdSpan("use set_alarm_now here")), MarkdownParser.spans("use set_alarm_now here"))
    }
}
